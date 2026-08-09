// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  requestWebSocketTicket,
  WebSocketTicketRequestError,
  WebSocketTicketTimeoutError
} from './websocketTicket'

describe('WebSocket ticket HTTP client', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.clearAllTimers()
    vi.useRealTimers()
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
      signal: expect.any(AbortSignal)
    })
    expect(vi.getTimerCount()).toBe(0)
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

  it('aborts and classifies a hanging fetch at the injected timeout', async () => {
    let fetchSignal
    vi.stubGlobal('fetch', vi.fn((_url, { signal }) => {
      fetchSignal = signal
      return new Promise((resolve, reject) => {
        signal.addEventListener('abort', () => {
          reject(new DOMException('Aborted', 'AbortError'))
        }, { once: true })
        window.setTimeout(() => resolve({
          ok: true,
          status: 200,
          json: async () => ({ code: 200, data: { ticket: 'too-late' } })
        }), 26)
      })
    }))

    const request = requestWebSocketTicket('valid.jwt', { timeoutMs: 25 })
    const rejection = expect(request).rejects.toBeInstanceOf(WebSocketTicketTimeoutError)
    await vi.advanceTimersByTimeAsync(26)

    await rejection
    expect(fetchSignal.aborted).toBe(true)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('preserves an external lifecycle abort and removes its listener and timeout', async () => {
    const lifecycle = new AbortController()
    const removeListener = vi.spyOn(lifecycle.signal, 'removeEventListener')
    vi.stubGlobal('fetch', vi.fn((_url, { signal }) => new Promise((_resolve, reject) => {
      signal.addEventListener('abort', () => {
        reject(new DOMException('Aborted', 'AbortError'))
      }, { once: true })
    })))

    const request = requestWebSocketTicket('valid.jwt', {
      signal: lifecycle.signal,
      timeoutMs: 25
    })
    const rejection = expect(request).rejects.toMatchObject({ name: 'AbortError' })
    lifecycle.abort()

    await rejection
    expect(removeListener).toHaveBeenCalledWith('abort', expect.any(Function))
    expect(vi.getTimerCount()).toBe(0)
  })
})
