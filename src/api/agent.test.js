// @vitest-environment jsdom

import { beforeEach, describe, expect, it, vi } from 'vitest'

const { get, post, put, deleteRequest } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  deleteRequest: vi.fn(),
}))

vi.mock('../utils/request', () => ({
  default: { get, post, put, delete: deleteRequest },
}))

import {
  cancelAgentTurn,
  createAgentMemory,
  createAgentTurn,
  createTemporarySession,
  deleteTemporarySession,
  getAgentTurn,
  getTemporarySession,
  getAgentMemories,
  getAgentMemorySetting,
  updateAgentMemory,
  setAgentMemoryState,
  updateAgentMemorySetting,
  deleteAgentMemory,
  resetAgentConversationContext,
  streamAgentTurnEvents,
  updateAgentMemoryExpiry,
} from './agent'

const agentRaw = Object.freeze({
  rawResponse: true,
  baseURL: 'http://localhost:18081',
})

describe('Agent 前端接口契约', () => {
  beforeEach(() => {
    get.mockReset()
    post.mockReset()
    put.mockReset()
    deleteRequest.mockReset()
  })

  it('uses owner-scoped raw endpoints for memory management', async () => {
    get.mockResolvedValue([])
    post.mockResolvedValue({ id: 8, version: 1 })
    put.mockResolvedValue({ id: 7, version: 2 })
    deleteRequest.mockResolvedValue(undefined)

    await getAgentMemories()
    await getAgentMemorySetting()
    await createAgentMemory({ category: 'GOAL', content: '完成 Agent', expiresAt: null })
    await updateAgentMemory(7, { content: '偏好简洁回答', expectedVersion: 1 })
    await setAgentMemoryState(7, { paused: true, expectedVersion: 2 })
    await updateAgentMemoryExpiry(7, { expiresAt: null, expectedVersion: 2 })
    await updateAgentMemorySetting({ enabled: false, expectedVersion: 0 })
    await deleteAgentMemory(7)

    expect(get).toHaveBeenNthCalledWith(1, '/api/agent/memories', agentRaw)
    expect(get).toHaveBeenNthCalledWith(2, '/api/agent/memory-settings', agentRaw)
    expect(post).toHaveBeenCalledWith('/api/agent/memories', {
      category: 'GOAL', content: '完成 Agent', expiresAt: null,
    }, agentRaw)
    expect(put).toHaveBeenNthCalledWith(1, '/api/agent/memories/7', {
      content: '偏好简洁回答', expectedVersion: 1,
    }, agentRaw)
    expect(put).toHaveBeenNthCalledWith(2, '/api/agent/memories/7/state', {
      paused: true, expectedVersion: 2,
    }, agentRaw)
    expect(put).toHaveBeenNthCalledWith(3, '/api/agent/memories/7/expiry', {
      expiresAt: null, expectedVersion: 2,
    }, agentRaw)
    expect(put).toHaveBeenNthCalledWith(4, '/api/agent/memory-settings', {
      enabled: false, expectedVersion: 0,
    }, agentRaw)
    expect(deleteRequest).toHaveBeenCalledWith('/api/agent/memories/7', agentRaw)
  })

  it('uses the raw temporary-session endpoints without persisting content in the browser', async () => {
    post.mockResolvedValue({ sessionId: 'session-1' })
    get.mockResolvedValue({ sessionId: 'session-1' })
    deleteRequest.mockResolvedValue(undefined)

    await createTemporarySession()
    await getTemporarySession()
    await deleteTemporarySession()

    expect(post).toHaveBeenNthCalledWith(1, '/api/agent/temporary-sessions', undefined, agentRaw)
    expect(get).toHaveBeenCalledWith('/api/agent/temporary-sessions', {
      ...agentRaw,
      silent: true,
    })
    expect(deleteRequest).toHaveBeenCalledWith('/api/agent/temporary-sessions', agentRaw)
  })

  it('sends the exact persistent and temporary turn payloads', async () => {
    post.mockResolvedValue({ turnId: -1 })

    await createAgentTurn({
      clientRequestId: 'request-1',
      message: '请解释这段代码',
      temporary: true,
      temporarySessionId: 'session-1',
      context: { page: 'article' },
    })
    await getAgentTurn(-1)
    await cancelAgentTurn(-1)
    await resetAgentConversationContext()

    expect(post).toHaveBeenNthCalledWith(1, '/api/agent/turns', {
      clientRequestId: 'request-1',
      message: '请解释这段代码',
      temporary: true,
      temporarySessionId: 'session-1',
      context: { page: 'article' },
    }, agentRaw)
    expect(get).toHaveBeenCalledWith('/api/agent/turns/-1', agentRaw)
    expect(post).toHaveBeenNthCalledWith(2, '/api/agent/turns/-1/cancel', undefined, {
      ...agentRaw,
    })
    expect(post).toHaveBeenNthCalledWith(3, '/api/agent/turns/context/reset', undefined, {
      ...agentRaw,
    })
  })

  it('streams authenticated SSE frames and forwards the resume cursor', async () => {
    const encoder = new TextEncoder()
    const chunks = [
      'id: 1-0\nevent: accepted\ndata: {"payload":{"state":"RUNNING"}}\n\n',
      'id: 2-0\nevent: done\ndata: {"payload":{"finalMessage":"完成"}}\n\n',
    ]
    const reader = {
      read: vi.fn()
        .mockResolvedValueOnce({ done: false, value: encoder.encode(chunks[0]) })
        .mockResolvedValueOnce({ done: false, value: encoder.encode(chunks[1]) })
        .mockResolvedValueOnce({ done: true }),
    }
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      body: { getReader: () => reader },
    })
    vi.stubGlobal('fetch', fetchMock)
    localStorage.setItem('token', 'agent-token')
    const events = []

    await streamAgentTurnEvents(-9, {
      after: '0-0',
      onEvent: event => events.push(event),
    })

    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('http://localhost:18081/api/agent/turns/-9/events?after=0-0'),
      expect.objectContaining({
        headers: expect.objectContaining({ token: 'agent-token' }),
      }),
    )
    expect(events.map(event => event.type)).toEqual(['accepted', 'done'])
    vi.unstubAllGlobals()
  })
})
