import request from '../utils/request'
import { createAgentSseParser } from '../utils/agentSse'

// Agent 控制器返回标准 JSON 或 204，因此每个请求都显式启用 rawResponse，
// 防止它被旧接口的 code/data/msg 响应拦截规则误判为业务失败。
const raw = Object.freeze({ rawResponse: true })

/** 创建或复用当前用户唯一的临时会话，后端不会因重复创建而滑动延长有效期。 */
export const createTemporarySession = () =>
  request.post('/api/agent/temporary-sessions', undefined, raw)

/** 静默查询当前临时会话；页面初始化时的 404 是正常的“尚未开启”状态。 */
export const getTemporarySession = () =>
  request.get('/api/agent/temporary-sessions', { ...raw, silent: true })

/** 删除临时会话及其可丢弃内容；活动回答尚未结束时后端会拒绝删除。 */
export const deleteTemporarySession = () =>
  request.delete('/api/agent/temporary-sessions', raw)

/**
 * 创建一次 Agent turn。调用方必须自行生成 clientRequestId，以便网络重试时保持幂等。
 * 临时模式必须同时提供 temporarySessionId，普通模式则不应携带该字段。
 */
export const createAgentTurn = payload =>
  request.post('/api/agent/turns', payload, raw)

/** 查询 turn 的权威快照，刷新页面或 SSE 中断时以该结果恢复界面。 */
export const getAgentTurn = turnId =>
  request.get(`/api/agent/turns/${turnId}`, raw)

/** 请求取消当前 turn；后端使用 run fence 防止迟到请求取消另一轮回答。 */
export const cancelAgentTurn = turnId =>
  request.post(`/api/agent/turns/${turnId}/cancel`, undefined, raw)

/**
 * 通过 fetch 读取 Agent 的 SSE 流。
 * axios 在当前项目中用于 JSON 请求；流式正文改用 ReadableStream，才能在完整响应结束前逐帧更新界面。
 */
export async function streamAgentTurnEvents(turnId, { after, onEvent, signal } = {}) {
  const apiBase = import.meta.env.VITE_API_BASE_URL || 'http://localhost:18080'
  const url = new URL(`/api/agent/turns/${turnId}/events`, apiBase)
  if (after) url.searchParams.set('after', after)

  const token = localStorage.getItem('token')
  const response = await fetch(url.toString(), {
    method: 'GET',
    headers: {
      Accept: 'text/event-stream',
      ...(token ? { token } : {}),
    },
    signal,
  })
  if (!response.ok || !response.body) {
    const error = new Error(`Agent 事件流连接失败（${response.status}）`)
    error.status = response.status
    throw error
  }

  const parser = createAgentSseParser(onEvent || (() => {}))
  const decoder = new TextDecoder()
  const reader = response.body.getReader()
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      // stream=true 会保留跨字节块的 UTF-8 多字节字符，避免中文被替换成乱码。
      parser.push(decoder.decode(value, { stream: true }))
    }
    parser.push(decoder.decode())
    parser.finish()
  } finally {
    reader.releaseLock?.()
  }
}
