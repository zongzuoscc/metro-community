// @vitest-environment jsdom

import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  requestUse: vi.fn(),
  responseUse: vi.fn(),
  messageError: vi.fn(),
  routerPush: vi.fn(),
  closeWebSocket: vi.fn(),
}))

vi.mock('axios', () => ({
  default: {
    create: vi.fn(() => ({
      interceptors: {
        request: { use: mocks.requestUse },
        response: { use: mocks.responseUse },
      },
    })),
  },
}))

vi.mock('element-plus', () => ({ ElMessage: { error: mocks.messageError } }))
vi.mock('../router', () => ({ default: { push: mocks.routerPush } }))
vi.mock('./websocket', () => ({ closeWebSocket: mocks.closeWebSocket }))

await import('./request')

describe('request authentication failure handling', () => {
  beforeEach(() => {
    localStorage.clear()
    mocks.messageError.mockClear()
    mocks.routerPush.mockClear()
    mocks.closeWebSocket.mockClear()
  })

  it('clears authentication and closes WebSocket for silent 401 requests', async () => {
    localStorage.setItem('token', 'expired-token')
    localStorage.setItem('user', JSON.stringify({ id: 1 }))
    const errorHandler = mocks.responseUse.mock.calls[0][1]
    const error = { config: { silent: true }, response: { status: 401 } }

    await expect(errorHandler(error)).rejects.toBe(error)

    expect(localStorage.getItem('token')).toBeNull()
    expect(localStorage.getItem('user')).toBeNull()
    expect(mocks.closeWebSocket).toHaveBeenCalledOnce()
    expect(mocks.routerPush).toHaveBeenCalledWith('/login')
  })

  it('returns raw Agent payloads and accepts an empty 204 response when explicitly requested', () => {
    const successHandler = mocks.responseUse.mock.calls[0][0]

    expect(successHandler({
      data: { sessionId: 'temporary-session', expiresAt: '2026-08-13T08:00:00Z' },
      config: { rawResponse: true },
      status: 201,
    })).toEqual({ sessionId: 'temporary-session', expiresAt: '2026-08-13T08:00:00Z' })

    expect(successHandler({
      data: undefined,
      config: { rawResponse: true },
      status: 204,
    })).toBeUndefined()
  })

  it('does not show a generic network toast for raw Agent errors handled by the feature UI', async () => {
    const errorHandler = mocks.responseUse.mock.calls[0][1]
    const error = {
      message: 'Request failed with status code 503',
      config: { rawResponse: true },
      response: { status: 503 },
    }

    await expect(errorHandler(error)).rejects.toBe(error)

    expect(mocks.messageError).not.toHaveBeenCalled()
  })
})
