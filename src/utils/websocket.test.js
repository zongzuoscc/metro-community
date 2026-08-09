// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  requestTicket: vi.fn(),
  routerPush: vi.fn(() => Promise.resolve())
}))

vi.mock('./websocketTicket', () => ({
  requestWebSocketTicket: mocks.requestTicket
}))

vi.mock('../router', () => ({
  default: {
    currentRoute: { value: { path: '/home' } },
    push: mocks.routerPush
  }
}))

import { buildWebSocketUrl, closeWebSocket, initWebSocket } from './websocket'

class TestWebSocket {
  static OPEN = 1

  constructor(url) {
    this.url = url
    this.readyState = 0
    TestWebSocket.instances.push(this)
  }

  close() {
    this.readyState = 3
    this.onclose?.()
  }

  send() {}
}

TestWebSocket.instances = []

const flushAsync = async () => {
  await Promise.resolve()
  await Promise.resolve()
  await Promise.resolve()
}

describe('WebSocket one-time ticket lifecycle', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    TestWebSocket.instances = []
    globalThis.WebSocket = TestWebSocket
    localStorage.clear()
    mocks.requestTicket.mockReset()
    mocks.requestTicket.mockImplementation(async token => `ticket-for-${token}`)
    mocks.routerPush.mockClear()
  })

  afterEach(() => {
    closeWebSocket()
    vi.clearAllTimers()
    vi.useRealTimers()
  })

  it('builds the isolated WebSocket URL from a ticket rather than a JWT', () => {
    expect(buildWebSocketUrl('ticket-1', 'http://localhost:18080'))
      .toBe('ws://localhost:18080/im/ticket-1')
    expect(buildWebSocketUrl('ticket/value', 'https://community.example/api'))
      .toBe('wss://community.example/im/ticket%2Fvalue')
  })

  it('requests a ticket before connecting and never puts the JWT in the URI', async () => {
    mocks.requestTicket.mockResolvedValueOnce('opaque-ticket')

    initWebSocket('account.jwt.secret')
    await flushAsync()

    expect(mocks.requestTicket).toHaveBeenCalledOnce()
    expect(mocks.requestTicket).toHaveBeenCalledWith('account.jwt.secret', expect.any(Object))
    expect(TestWebSocket.instances).toHaveLength(1)
    expect(TestWebSocket.instances[0].url).toBe('ws://localhost:18080/im/opaque-ticket')
    expect(TestWebSocket.instances[0].url).not.toContain('account.jwt.secret')
  })

  it('coalesces concurrent initialization for the same account', async () => {
    let resolveTicket
    mocks.requestTicket.mockReturnValueOnce(new Promise(resolve => { resolveTicket = resolve }))

    expect(initWebSocket('same.jwt')).toBe(true)
    expect(initWebSocket('same.jwt')).toBe(false)
    expect(mocks.requestTicket).toHaveBeenCalledOnce()

    resolveTicket('single-ticket')
    await flushAsync()

    expect(TestWebSocket.instances).toHaveLength(1)
  })

  it('invalidates a pending ticket response after explicit close', async () => {
    let resolveTicket
    mocks.requestTicket.mockReturnValueOnce(new Promise(resolve => { resolveTicket = resolve }))

    initWebSocket('account.jwt')
    closeWebSocket()
    resolveTicket('late-ticket')
    await flushAsync()

    expect(TestWebSocket.instances).toHaveLength(0)
    vi.advanceTimersByTime(10_000)
    expect(mocks.requestTicket).toHaveBeenCalledOnce()
  })

  it('invalidates the old account pending response when accounts switch', async () => {
    let resolveAccountA
    mocks.requestTicket.mockImplementation(token => {
      if (token === 'account-a.jwt') {
        return new Promise(resolve => { resolveAccountA = resolve })
      }
      return Promise.resolve('account-b-ticket')
    })

    initWebSocket('account-a.jwt')
    initWebSocket('account-b.jwt')
    await flushAsync()
    resolveAccountA('late-account-a-ticket')
    await flushAsync()

    expect(TestWebSocket.instances).toHaveLength(1)
    expect(TestWebSocket.instances[0].url).toBe('ws://localhost:18080/im/account-b-ticket')
  })

  it('closes the previous account socket and ignores its queued tail message', async () => {
    const received = []
    const listener = event => received.push(event.detail)
    window.addEventListener('on-chat-msg', listener)
    mocks.requestTicket
      .mockResolvedValueOnce('account-a-ticket')
      .mockResolvedValueOnce('account-b-ticket')
    initWebSocket('account-a.jwt')
    await flushAsync()
    const firstSocket = TestWebSocket.instances[0]

    initWebSocket('account-b.jwt')
    await flushAsync()
    firstSocket.onmessage?.({ data: JSON.stringify({ content: 'account A secret' }) })

    expect(firstSocket.readyState).toBe(3)
    expect(received).toEqual([])
    expect(TestWebSocket.instances[1].url).toBe('ws://localhost:18080/im/account-b-ticket')
    window.removeEventListener('on-chat-msg', listener)
  })

  it('requests a fresh one-time ticket for every reconnect attempt', async () => {
    let ticketNumber = 0
    mocks.requestTicket.mockImplementation(async () => `ticket-${++ticketNumber}`)
    initWebSocket('valid.jwt')
    await flushAsync()
    const first = TestWebSocket.instances[0]

    first.onclose?.()
    vi.advanceTimersByTime(3000)
    await flushAsync()

    expect(mocks.requestTicket).toHaveBeenCalledTimes(2)
    expect(TestWebSocket.instances.map(socket => socket.url)).toEqual([
      'ws://localhost:18080/im/ticket-1',
      'ws://localhost:18080/im/ticket-2'
    ])
  })

  it('treats ticket HTTP 401 as terminal and clears the authenticated session', async () => {
    localStorage.setItem('token', 'expired.jwt')
    localStorage.setItem('user', JSON.stringify({ id: 1 }))
    mocks.requestTicket.mockRejectedValueOnce(Object.assign(new Error('unauthorized'), { status: 401 }))

    initWebSocket('expired.jwt')
    await flushAsync()
    await vi.dynamicImportSettled()
    vi.advanceTimersByTime(30_000)
    await flushAsync()

    expect(localStorage.getItem('token')).toBeNull()
    expect(localStorage.getItem('user')).toBeNull()
    expect(mocks.routerPush).toHaveBeenCalledWith('/login')
    expect(mocks.requestTicket).toHaveBeenCalledOnce()
    expect(TestWebSocket.instances).toHaveLength(0)
  })

  it('keeps network and HTTP 503 ticket retries bounded', async () => {
    mocks.requestTicket.mockRejectedValue(Object.assign(new Error('unavailable'), { status: 503 }))
    initWebSocket('valid.jwt')
    await flushAsync()

    for (let attempt = 0; attempt < 6; attempt += 1) {
      vi.advanceTimersByTime(3000)
      await flushAsync()
    }

    expect(mocks.requestTicket).toHaveBeenCalledTimes(6)
    expect(TestWebSocket.instances).toHaveLength(0)
  })

  it('keeps handshake rejection retries bounded while using a new ticket each time', async () => {
    let ticketNumber = 0
    mocks.requestTicket.mockImplementation(async () => `ticket-${++ticketNumber}`)
    initWebSocket('valid.jwt')
    await flushAsync()

    for (let attempt = 0; attempt < 7; attempt += 1) {
      const socket = TestWebSocket.instances.at(-1)
      socket.onopen?.()
      socket.onclose?.()
      vi.advanceTimersByTime(3000)
      await flushAsync()
    }

    expect(TestWebSocket.instances).toHaveLength(6)
    expect(mocks.requestTicket).toHaveBeenCalledTimes(6)
    expect(new Set(TestWebSocket.instances.map(socket => socket.url))).toHaveProperty('size', 6)
  })

  it('starts a fresh retry budget after a connection remains stable', async () => {
    let ticketNumber = 0
    mocks.requestTicket.mockImplementation(async () => `ticket-${++ticketNumber}`)
    initWebSocket('valid.jwt')
    await flushAsync()

    for (let attempt = 0; attempt < 4; attempt += 1) {
      TestWebSocket.instances.at(-1).onclose?.()
      vi.advanceTimersByTime(3000)
      await flushAsync()
    }

    const stableSocket = TestWebSocket.instances.at(-1)
    stableSocket.readyState = TestWebSocket.OPEN
    stableSocket.onopen?.()
    vi.advanceTimersByTime(30_000)
    stableSocket.onclose?.()
    vi.advanceTimersByTime(3000)
    await flushAsync()

    TestWebSocket.instances.at(-1).onclose?.()
    vi.advanceTimersByTime(3000)
    await flushAsync()

    expect(TestWebSocket.instances).toHaveLength(7)
    expect(mocks.requestTicket).toHaveBeenCalledTimes(7)
  })
})
