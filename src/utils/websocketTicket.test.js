// @vitest-environment jsdom

import { afterEach, describe, expect, it, vi } from 'vitest'
import { requestWebSocketTicket, WebSocketTicketRequestError } from './websocketTicket'

describe('WebSocket ticket HTTP client', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('uses a Bearer JWT only on the protected ticket request', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ code: 200, data: { ticket: 'opaque-ticket' } })
    })
    vi.stubGlobal('fetch', fetchMock)
    const abortController = new AbortController()

    const ticket = await requestWebSocketTicket('login.jwt', {
      backendBaseUrl: 'http://localhost:18080',
      signal: abortController.signal
    })

    expect(ticket).toBe('opaque-ticket')
    expect(fetchMock).toHaveBeenCalledWith('http://localhost:18080/api/ws/ticket', {
      method: 'POST',
      headers: { Authorization: 'Bearer login.jwt' },
      signal: abortController.signal
    })
  })

  it('surfaces HTTP 401 as a terminal authentication error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      json: async () => ({ code: 401, msg: '未登录或登录已过期' })
    }))

    await expect(requestWebSocketTicket('expired.jwt'))
      .rejects.toMatchObject({ status: 401, name: 'WebSocketTicketRequestError' })
  })

  it('surfaces HTTP 503 without fabricating a ticket', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 503,
      json: async () => ({ code: 503, msg: '实时连接暂不可用' })
    }))

    await expect(requestWebSocketTicket('valid.jwt'))
      .rejects.toBeInstanceOf(WebSocketTicketRequestError)
  })

  it('rejects a successful response that does not contain a ticket', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ code: 200, data: {} })
    }))

    await expect(requestWebSocketTicket('valid.jwt'))
      .rejects.toMatchObject({ status: 200 })
  })
})
