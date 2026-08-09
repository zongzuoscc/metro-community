// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
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

describe('WebSocket connection lifecycle', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    TestWebSocket.instances = []
    globalThis.WebSocket = TestWebSocket
  })

  afterEach(() => {
    closeWebSocket()
    vi.clearAllTimers()
    vi.useRealTimers()
  })

  it('derives the WebSocket endpoint from the configured isolated API origin', () => {
    expect(buildWebSocketUrl('jwt.token', 'http://localhost:18080'))
      .toBe('ws://localhost:18080/im/jwt.token')
    expect(buildWebSocketUrl('jwt/token', 'https://community.example/api'))
      .toBe('wss://community.example/im/jwt%2Ftoken')
  })

  it('uses the isolated backend default instead of hard-coded port 8080', () => {
    initWebSocket('jwt.token')

    expect(TestWebSocket.instances).toHaveLength(1)
    expect(TestWebSocket.instances[0].url).toBe('ws://localhost:18080/im/jwt.token')
  })

  it('does not reconnect after an explicit logout close', () => {
    initWebSocket('jwt.token')
    closeWebSocket()
    vi.advanceTimersByTime(10_000)

    expect(TestWebSocket.instances).toHaveLength(1)
  })

  it('replaces an existing connection when a different account logs in', () => {
    initWebSocket('account-a.jwt')
    const firstSocket = TestWebSocket.instances[0]

    initWebSocket('account-b.jwt')

    expect(firstSocket.readyState).toBe(3)
    expect(TestWebSocket.instances).toHaveLength(2)
    expect(TestWebSocket.instances[1].url).toBe('ws://localhost:18080/im/account-b.jwt')
  })

  it('ignores a queued tail message from the account that was just replaced', () => {
    const received = []
    const listener = event => received.push(event.detail)
    window.addEventListener('on-chat-msg', listener)
    initWebSocket('account-a.jwt')
    const firstSocket = TestWebSocket.instances[0]

    initWebSocket('account-b.jwt')
    firstSocket.onmessage?.({ data: JSON.stringify({ content: 'account A secret' }) })

    expect(received).toEqual([])
    window.removeEventListener('on-chat-msg', listener)
  })

  it('stops retrying when the server accepts a handshake and immediately rejects the token', () => {
    initWebSocket('expired.jwt')

    for (let attempt = 0; attempt < 7; attempt += 1) {
      const socket = TestWebSocket.instances.at(-1)
      socket.onopen?.()
      socket.onclose?.()
      vi.advanceTimersByTime(3000)
    }

    expect(TestWebSocket.instances).toHaveLength(6)
  })

  it('starts a fresh retry budget after a connection remains stable', () => {
    initWebSocket('valid.jwt')

    for (let attempt = 0; attempt < 4; attempt += 1) {
      TestWebSocket.instances.at(-1).onclose?.()
      vi.advanceTimersByTime(3000)
    }

    const stableSocket = TestWebSocket.instances.at(-1)
    stableSocket.readyState = TestWebSocket.OPEN
    stableSocket.onopen?.()
    vi.advanceTimersByTime(30_000)
    stableSocket.onclose?.()
    vi.advanceTimersByTime(3000)

    TestWebSocket.instances.at(-1).onclose?.()
    vi.advanceTimersByTime(3000)

    expect(TestWebSocket.instances).toHaveLength(7)
  })
})
