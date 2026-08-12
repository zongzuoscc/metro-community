// @vitest-environment jsdom

import { beforeEach, describe, expect, it, vi } from 'vitest'

const { get, post, deleteRequest } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  deleteRequest: vi.fn(),
}))

vi.mock('../utils/request', () => ({
  default: { get, post, delete: deleteRequest },
}))

import {
  cancelAgentTurn,
  createAgentTurn,
  createTemporarySession,
  deleteTemporarySession,
  getAgentTurn,
  getTemporarySession,
  streamAgentTurnEvents,
} from './agent'

describe('Agent 前端接口契约', () => {
  beforeEach(() => {
    get.mockReset()
    post.mockReset()
    deleteRequest.mockReset()
  })

  it('uses the raw temporary-session endpoints without persisting content in the browser', async () => {
    post.mockResolvedValue({ sessionId: 'session-1' })
    get.mockResolvedValue({ sessionId: 'session-1' })
    deleteRequest.mockResolvedValue(undefined)

    await createTemporarySession()
    await getTemporarySession()
    await deleteTemporarySession()

    expect(post).toHaveBeenNthCalledWith(1, '/api/agent/temporary-sessions', undefined, {
      rawResponse: true,
    })
    expect(get).toHaveBeenCalledWith('/api/agent/temporary-sessions', {
      rawResponse: true,
      silent: true,
    })
    expect(deleteRequest).toHaveBeenCalledWith('/api/agent/temporary-sessions', {
      rawResponse: true,
    })
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

    expect(post).toHaveBeenNthCalledWith(1, '/api/agent/turns', {
      clientRequestId: 'request-1',
      message: '请解释这段代码',
      temporary: true,
      temporarySessionId: 'session-1',
      context: { page: 'article' },
    }, { rawResponse: true })
    expect(get).toHaveBeenCalledWith('/api/agent/turns/-1', { rawResponse: true })
    expect(post).toHaveBeenNthCalledWith(2, '/api/agent/turns/-1/cancel', undefined, {
      rawResponse: true,
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
      expect.stringContaining('/api/agent/turns/-9/events?after=0-0'),
      expect.objectContaining({
        headers: expect.objectContaining({ token: 'agent-token' }),
      }),
    )
    expect(events.map(event => event.type)).toEqual(['accepted', 'done'])
    vi.unstubAllGlobals()
  })
})
