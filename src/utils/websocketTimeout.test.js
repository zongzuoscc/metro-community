// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('element-plus', () => ({ ElNotification: vi.fn() }))

import { closeWebSocket, initWebSocket } from './websocket'

class TestWebSocket {
  static OPEN = 1
  static instances = []

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

const ticketResponse = ticket => ({
  ok: true,
  status: 200,
  json: async () => ({ code: 200, data: { ticket } })
})

const flushAsync = async () => {
  await Promise.resolve()
  await Promise.resolve()
  await Promise.resolve()
}

describe('WebSocket hanging ticket recovery', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    TestWebSocket.instances = []
    globalThis.WebSocket = TestWebSocket
    localStorage.clear()
  })

  afterEach(() => {
    closeWebSocket()
    vi.unstubAllGlobals()
    vi.clearAllTimers()
    vi.useRealTimers()
  })

  it('times out hanging fetches, requests again, and keeps total retries bounded', async () => {
    const fetchMock = vi.fn((_url, { signal }) => {
      const requestNumber = fetchMock.mock.calls.length
      return new Promise((resolve, reject) => {
        signal.addEventListener('abort', () => {
          reject(new DOMException('Aborted', 'AbortError'))
        }, { once: true })
        window.setTimeout(() => resolve(ticketResponse(`late-ticket-${requestNumber}`)), 10_001)
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    initWebSocket('valid.jwt')
    for (let attempt = 0; attempt < 6; attempt += 1) {
      await vi.advanceTimersByTimeAsync(10_000)
      await flushAsync()
      await vi.advanceTimersByTimeAsync(3000)
      await flushAsync()
    }

    expect(fetchMock).toHaveBeenCalledTimes(6)
    expect(TestWebSocket.instances).toHaveLength(0)
  })

  it('does not retry when lifecycle close aborts a hanging fetch', async () => {
    let fetchSignal
    const fetchMock = vi.fn((_url, { signal }) => {
      fetchSignal = signal
      return new Promise((_resolve, reject) => {
        signal.addEventListener('abort', () => {
          reject(new DOMException('Aborted', 'AbortError'))
        }, { once: true })
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    initWebSocket('valid.jwt')

    closeWebSocket()
    await flushAsync()
    await vi.advanceTimersByTimeAsync(30_000)
    await flushAsync()

    expect(fetchSignal.aborted).toBe(true)
    expect(fetchMock).toHaveBeenCalledOnce()
    expect(TestWebSocket.instances).toHaveLength(0)
  })
})
