import { ElNotification } from 'element-plus'
import { requestWebSocketTicket } from './websocketTicket'

const DEFAULT_API_BASE_URL = 'http://localhost:18080'
const MAX_RETRIES = 5
const RETRY_DELAY_MS = 3000
const STABLE_CONNECTION_MS = 30_000
const REPLACED_CLOSE_CODE = 1000
const REPLACED_CLOSE_REASON = 'Replaced by a new connection'

let websocket = null
let retryCount = 0
let reconnectTimer = null
let stableConnectionTimer = null
let stableConnectionOwner = null
let activeToken = null
let manuallyClosed = false
let lifecycleGeneration = 0
let pendingConnection = null

export const buildWebSocketUrl = (
    ticket,
    backendBaseUrl = import.meta.env.VITE_WS_BASE_URL
        || import.meta.env.VITE_API_BASE_URL
        || DEFAULT_API_BASE_URL,
    pageOrigin = window.location.origin
) => {
    if (!ticket) throw new Error('WebSocket ticket is required')
    const url = new URL(backendBaseUrl, pageOrigin)
    if (url.protocol === 'http:') url.protocol = 'ws:'
    else if (url.protocol === 'https:') url.protocol = 'wss:'
    else if (url.protocol !== 'ws:' && url.protocol !== 'wss:') {
        throw new Error('WebSocket backend must use http, https, ws, or wss')
    }
    url.pathname = `/im/${encodeURIComponent(ticket)}`
    url.search = ''
    url.hash = ''
    return url.toString()
}

const isCurrentLifecycle = (token, generation) => (
    !manuallyClosed
    && activeToken === token
    && lifecycleGeneration === generation
)

const scheduleReconnect = (token, generation) => {
    if (!isCurrentLifecycle(token, generation) || retryCount >= MAX_RETRIES) return
    retryCount += 1
    reconnectTimer = window.setTimeout(() => {
        reconnectTimer = null
        if (isCurrentLifecycle(token, generation)) startConnection(token, generation)
    }, RETRY_DELAY_MS)
}

const expireAuthentication = () => {
    localStorage.removeItem('token')
    localStorage.removeItem('user')
    closeWebSocket()
    import('../router').then(({ default: router }) => {
        if (router.currentRoute.value.path !== '/login') return router.push('/login')
        return undefined
    }).catch(() => {})
}

const openWebSocket = (ticket, token, generation) => {
    if (!isCurrentLifecycle(token, generation)) return
    const socket = new WebSocket(buildWebSocketUrl(ticket))
    websocket = socket

    socket.onopen = () => {
        if (!isCurrentLifecycle(token, generation) || websocket !== socket) {
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
            if (isCurrentLifecycle(token, generation)
                && websocket === socket
                && socket.readyState === WebSocket.OPEN) {
                retryCount = 0
            }
        }, STABLE_CONNECTION_MS)
    }

    socket.onmessage = (event) => {
        if (!isCurrentLifecycle(token, generation) || websocket !== socket) return
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
        } catch (error) {
            console.error('消息解析失败', error)
        }
    }

    socket.onclose = (event) => {
        if (stableConnectionOwner === socket) {
            if (stableConnectionTimer != null) window.clearTimeout(stableConnectionTimer)
            stableConnectionTimer = null
            stableConnectionOwner = null
        }
        if (!isCurrentLifecycle(token, generation) || websocket !== socket) return
        websocket = null
        if (event?.code === REPLACED_CLOSE_CODE && event?.reason === REPLACED_CLOSE_REASON) return
        scheduleReconnect(token, generation)
    }

    socket.onerror = () => {
        if (isCurrentLifecycle(token, generation) && websocket === socket) {
            console.warn('WebSocket 暂时不可用，等待重连')
        }
    }
}

const startConnection = (token, generation) => {
    if (!isCurrentLifecycle(token, generation) || pendingConnection != null) return

    const abortController = new AbortController()
    const attempt = { generation, abortController, promise: null }
    pendingConnection = attempt
    attempt.promise = requestWebSocketTicket(token, { signal: abortController.signal })
        .then(ticket => {
            if (isCurrentLifecycle(token, generation) && pendingConnection === attempt) {
                openWebSocket(ticket, token, generation)
            }
        })
        .catch(error => {
            if (!isCurrentLifecycle(token, generation) || pendingConnection !== attempt) return
            if (error?.status === 401) {
                expireAuthentication()
                return
            }
            if (error?.name !== 'AbortError') {
                console.warn('WebSocket ticket 暂时不可用，等待重连')
                scheduleReconnect(token, generation)
            }
        })
        .finally(() => {
            if (pendingConnection === attempt) pendingConnection = null
        })
}

export const initWebSocket = (token) => {
    if (!token) return false
    if (activeToken === token && (websocket || pendingConnection || reconnectTimer != null)) return false
    if (activeToken != null && activeToken !== token) closeWebSocket()

    lifecycleGeneration += 1
    manuallyClosed = false
    activeToken = token
    retryCount = 0
    if (reconnectTimer != null) window.clearTimeout(reconnectTimer)
    reconnectTimer = null
    startConnection(token, lifecycleGeneration)
    return true
}

export const sendWebSocketMessage = (toId, content) => {
    if (websocket && websocket.readyState === WebSocket.OPEN) {
        websocket.send(JSON.stringify({
            toId: Number(toId),
            content
        }))
        return true
    }
    console.error('WebSocket未连接')
    return false
}

export const closeWebSocket = () => {
    lifecycleGeneration += 1
    manuallyClosed = true
    activeToken = null
    retryCount = 0
    if (reconnectTimer != null) window.clearTimeout(reconnectTimer)
    reconnectTimer = null
    if (stableConnectionTimer != null) window.clearTimeout(stableConnectionTimer)
    stableConnectionTimer = null
    stableConnectionOwner = null
    const pending = pendingConnection
    pendingConnection = null
    pending?.abortController.abort()
    const socket = websocket
    websocket = null
    socket?.close()
}
