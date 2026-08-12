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

/** 按服务端发布指针总结文章，避免把页面 DOM 中可被篡改的文本当成事实源。 */
export const summarizeArticle = articleId =>
  request.post(`/api/agent/articles/${articleId}/summary`, undefined, raw)

/** 文章核心观点与争议点仍由后端读取权威发布正文，不能用没有上下文的普通问答代替。 */
export const analyzeArticle = (articleId, operation) =>
  request.post(`/api/agent/articles/${articleId}/analysis/${operation}`, undefined, raw)

/** 生成可审核的写作建议；后端只返回提案，不直接修改或发布草稿。 */
export const createWritingSuggestion = payload =>
  request.post('/api/agent/writing/suggestions', payload, raw)

/** 读取脱敏后的用户模型配置；接口永远不会返回 API Key 或密文。 */
export const getAiProviderSetting = () =>
  request.get('/api/agent/provider-settings', raw)

/** 保存或替换用户模型配置；空 apiKey 表示沿用后端现有密钥。 */
export const saveAiProviderSetting = payload =>
  request.put('/api/agent/provider-settings', payload, raw)

/** 临时停用或重新启用用户模型，停用后请求自动使用平台基础额度。 */
export const setAiProviderEnabled = enabled =>
  request.patch('/api/agent/provider-settings/enabled', { enabled }, raw)

/** 测试时只调用用户配置，后端不会静默回退平台模型。 */
export const testAiProviderConnection = () =>
  request.post('/api/agent/provider-settings/test', undefined, raw)

/** 永久删除当前用户保存的密文与模型设置。 */
export const deleteAiProviderSetting = () =>
  request.delete('/api/agent/provider-settings', raw)

/** 读取当前用户可管理的全部长期记忆，包含 ACTIVE 与 PAUSED，不返回已删除内容。 */
export const getAgentMemories = () =>
  request.get('/api/agent/memories', raw)

/** 读取长期记忆总开关及其乐观锁版本。 */
export const getAgentMemorySetting = () =>
  request.get('/api/agent/memory-settings', raw)

/** 编辑只会追加新的不可变版本，expectedVersion 防止覆盖并发修改。 */
export const updateAgentMemory = (memoryId, payload) =>
  request.put(`/api/agent/memories/${memoryId}`, payload, raw)

/** 暂停会立即切断召回，恢复不会丢失原内容和来源。 */
export const setAgentMemoryState = (memoryId, payload) =>
  request.put(`/api/agent/memories/${memoryId}/state`, payload, raw)

/** 开关全部长期记忆；关闭后 Agent 不会把任何记忆放入模型上下文。 */
export const updateAgentMemorySetting = payload =>
  request.put('/api/agent/memory-settings', payload, raw)

/** 删除单条记忆事实，并让后端进入派生向量清理流程。 */
export const deleteAgentMemory = memoryId =>
  request.delete(`/api/agent/memories/${memoryId}`, raw)

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
