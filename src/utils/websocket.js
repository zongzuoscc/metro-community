import { ElNotification } from 'element-plus'

let websocket = null
let retryCount = 0
// 用于存储消息回调函数
const messageHandlers = []

export const initWebSocket = (token) => {
    if (websocket) return // 避免重复连接

    // 注意：如果你的后端不是 localhost:8080，请修改这里
    const wsUrl = `ws://localhost:8080/im/${token}`

    websocket = new WebSocket(wsUrl)

    websocket.onopen = () => {
        console.log('✅ WebSocket连接成功')
        retryCount = 0
    }

    websocket.onmessage = (event) => {
        try {
            const msg = JSON.parse(event.data)

            // 1. 如果是聊天页面正在打开，分发给 Chat.vue 处理
            // 触发自定义事件，让组件去监听
            window.dispatchEvent(new CustomEvent('on-chat-msg', { detail: msg }))

            // 2. 如果不在聊天页，或者只是为了提示，可以弹窗
            // (简单的判断：如果当前路由不是 /chat，或者窗口最小化了)
            if (!window.location.hash.includes('#/chat')) {
                ElNotification({
                    title: '新私信',
                    message: `${msg.content.substring(0, 20)}...`,
                    type: 'success',
                    position: 'bottom-right',
                    duration: 3000,
                    onClick: () => { window.location.hash = '#/chat' } // 点击跳转
                })
            }
        } catch (e) {
            console.error('消息解析失败', e)
        }
    }

    websocket.onclose = () => {
        console.log('❌ WebSocket断开')
        websocket = null
        // 断线重连机制 (最多重试5次)
        if (retryCount < 5) {
            setTimeout(() => {
                retryCount++
                console.log(`尝试重连... (${retryCount})`)
                initWebSocket(token)
            }, 3000)
        }
    }

    websocket.onerror = (e) => {
        console.error('WebSocket Error', e)
    }
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
    if (websocket) {
        websocket.close()
        websocket = null
    }
}