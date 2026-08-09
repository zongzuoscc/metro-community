import { ElNotification } from 'element-plus'

const DEFAULT_API_BASE_URL = 'http://localhost:18080'
const MAX_RETRIES = 5
const RETRY_DELAY_MS = 3000
const STABLE_CONNECTION_MS = 30_000

let websocket = null
let retryCount = 0
let reconnectTimer = null
let stableConnectionTimer = null
let stableConnectionOwner = null
let activeToken = null
let manuallyClosed = false
// 用于存储消息回调函数
const messageHandlers = []

export const buildWebSocketUrl = (
    token,
    backendBaseUrl = import.meta.env.VITE_WS_BASE_URL
        || import.meta.env.VITE_API_BASE_URL
        || DEFAULT_API_BASE_URL,
    pageOrigin = window.location.origin
) => {
    if (!token) throw new Error('WebSocket token is required')
    const url = new URL(backendBaseUrl, pageOrigin)
    if (url.protocol === 'http:') url.protocol = 'ws:'
    else if (url.protocol === 'https:') url.protocol = 'wss:'
    else if (url.protocol !== 'ws:' && url.protocol !== 'wss:') {
        throw new Error('WebSocket backend must use http, https, ws, or wss')
    }
    url.pathname = `/im/${encodeURIComponent(token)}`
    url.search = ''
    url.hash = ''
    return url.toString()
}

const scheduleReconnect = (token) => {
    if (manuallyClosed || activeToken !== token || retryCount >= MAX_RETRIES) return
    retryCount += 1
    reconnectTimer = window.setTimeout(() => {
        reconnectTimer = null
        if (!manuallyClosed && activeToken === token) openWebSocket(token)
    }, RETRY_DELAY_MS)
}

const openWebSocket = (token) => {
    const socket = new WebSocket(buildWebSocketUrl(token))
    websocket = socket

    socket.onopen = () => {
        if (manuallyClosed || websocket !== socket || activeToken !== token) {
            socket.close()
            return
        }
        if (stableConnectionTimer != null) window.clearTimeout(stableConnectionTimer)
        stableConnectionOwner = socket
        stableConnectionTimer = window.setTimeout(() => {
            if (stableConnectionOwner === socket) {
                stableConnectionTimer = null
                stableConnectionOwner = null
            }
            if (!manuallyClosed && websocket === socket && socket.readyState === WebSocket.OPEN) {
                retryCount = 0
            }
        }, STABLE_CONNECTION_MS)
    }

    socket.onmessage = (event) => {
        if (manuallyClosed || websocket !== socket || activeToken !== token) return
        try {
            const msg = JSON.parse(event.data)

            window.dispatchEvent(new CustomEvent('on-chat-msg', { detail: msg }))

            if (window.location.pathname !== '/chat') {
                ElNotification({
                    title: '新私信',
                    message: `${msg.content.substring(0, 20)}...`,
                    type: 'success',
                    position: 'bottom-right',
                    duration: 3000,
                    onClick: () => { window.location.href = '/chat' }
                })
            }
        } catch (e) {
            console.error('消息解析失败', e)
        }
    }

    socket.onclose = () => {
        if (stableConnectionOwner === socket) {
            if (stableConnectionTimer != null) window.clearTimeout(stableConnectionTimer)
            stableConnectionTimer = null
            stableConnectionOwner = null
        }
        if (manuallyClosed || websocket !== socket || activeToken !== token) return
        websocket = null
        scheduleReconnect(token)
    }

    socket.onerror = () => {
        console.warn('WebSocket 暂时不可用，等待重连')
    }
}

export const initWebSocket = (token) => {
    if (!token) return false
    if (websocket && activeToken === token) return false
    if (activeToken && activeToken !== token) closeWebSocket()
    manuallyClosed = false
    activeToken = token
    if (reconnectTimer != null) window.clearTimeout(reconnectTimer)
    reconnectTimer = null
    openWebSocket(token)
    return true
}

// 发送消息
export const sendWebSocketMessage = (toId, content) => {
    if (websocket && websocket.readyState === WebSocket.OPEN) {
        // 对应后端的 ChatMsg 结构
        const msg = {
            toId: Number(toId),
            content: content
        }
        websocket.send(JSON.stringify(msg))
        return true
    } else {
        console.error('WebSocket未连接')
        return false
    }
}

// 关闭连接 (退出登录时调用)
export const closeWebSocket = () => {
    manuallyClosed = true
    activeToken = null
    retryCount = 0
    if (reconnectTimer != null) window.clearTimeout(reconnectTimer)
    reconnectTimer = null
    if (stableConnectionTimer != null) window.clearTimeout(stableConnectionTimer)
    stableConnectionTimer = null
    stableConnectionOwner = null
    const socket = websocket
    websocket = null
    socket?.close()
}
