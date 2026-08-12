// @vitest-environment jsdom

import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  routerPush: vi.fn(),
  createTemporarySession: vi.fn(),
  getTemporarySession: vi.fn(),
  deleteTemporarySession: vi.fn(),
  createAgentTurn: vi.fn(),
  getAgentTurn: vi.fn(),
  cancelAgentTurn: vi.fn(),
  streamAgentTurnEvents: vi.fn(),
  messageError: vi.fn(),
  messageSuccess: vi.fn(),
  messageWarning: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mocks.routerPush }),
}))

vi.mock('element-plus', () => ({
  ElMessage: {
    error: mocks.messageError,
    success: mocks.messageSuccess,
    warning: mocks.messageWarning,
  },
}))

vi.mock('../api/agent', () => ({
  createTemporarySession: mocks.createTemporarySession,
  getTemporarySession: mocks.getTemporarySession,
  deleteTemporarySession: mocks.deleteTemporarySession,
  createAgentTurn: mocks.createAgentTurn,
  getAgentTurn: mocks.getAgentTurn,
  cancelAgentTurn: mocks.cancelAgentTurn,
  streamAgentTurnEvents: mocks.streamAgentTurnEvents,
}))

const { default: Agent } = await import('./Agent.vue')
const slotStub = { template: '<span><slot /></span>' }
let wrapper

function storageValues(storage) {
  return Array.from({ length: storage.length }, (_, index) => {
    const key = storage.key(index)
    return key == null ? null : storage.getItem(key)
  }).filter(value => value != null)
}

function mountAgent() {
  wrapper = mount(Agent, {
    global: {
      stubs: {
        ElButton: {
          props: ['disabled', 'loading'],
          emits: ['click'],
          template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
        },
        ElIcon: slotStub,
        ArrowLeft: true,
        Hide: true,
        InfoFilled: true,
      },
    },
  })
  return wrapper
}

beforeEach(() => {
  localStorage.clear()
  localStorage.setItem('token', 'agent-token')
  sessionStorage.clear()
  Object.values(mocks).forEach(mock => mock.mockReset?.())
  mocks.getTemporarySession.mockRejectedValue({ response: { status: 404 } })
  mocks.createTemporarySession.mockResolvedValue({
    sessionId: 'session-1',
    createdAt: '2026-08-12T10:00:00Z',
    expiresAt: '2026-08-13T10:00:00Z',
  })
  mocks.deleteTemporarySession.mockResolvedValue(undefined)
  mocks.streamAgentTurnEvents.mockResolvedValue(undefined)
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  localStorage.clear()
})

describe('Agent 临时对话页面', () => {
  it('opens temporary mode with a persistent privacy notice and stores metadata only', async () => {
    mountAgent()
    await flushPromises()

    await wrapper.get('[data-test="temporary-toggle"]').trigger('click')
    await flushPromises()

    expect(mocks.createTemporarySession).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('临时对话已开启')
    expect(wrapper.text()).toContain('不会写入聊天历史和长期记忆')
    expect(sessionStorage.getItem('metro.agent.temporary.session')).toBe('session-1')
    expect(sessionStorage.getItem('metro.agent.temporary.messages')).toBeNull()
  })

  it('sends a temporary turn, renders the terminal answer, and never stores message content', async () => {
    mocks.createAgentTurn.mockResolvedValue({ turnId: -8, state: 'RUNNING', temporary: true })
    mocks.streamAgentTurnEvents.mockImplementation(async (_turnId, options) => {
      options.onEvent({
        id: '2-0',
        type: 'done',
        data: { payload: { finalMessage: '临时回答正文' } },
      })
    })
    mountAgent()
    await flushPromises()
    await wrapper.get('[data-test="temporary-toggle"]').trigger('click')
    await wrapper.get('[data-test="agent-input"]').setValue('不要记住这句话')
    await wrapper.get('[data-test="send-agent-message"]').trigger('click')
    await flushPromises()

    expect(mocks.createAgentTurn).toHaveBeenCalledWith(expect.objectContaining({
      message: '不要记住这句话',
      temporary: true,
      temporarySessionId: 'session-1',
    }))
    expect(wrapper.text()).toContain('不要记住这句话')
    expect(wrapper.text()).toContain('临时回答正文')
    expect(sessionStorage.getItem('metro.agent.temporary.turn')).toBe('-8')
    const browserStorage = [
      ...storageValues(sessionStorage),
      ...storageValues(localStorage),
    ].join('\n')
    expect(browserStorage).not.toContain('不要记住这句话')
    expect(browserStorage).not.toContain('临时回答正文')
  })

  it('admits only one turn while the first create request is still pending', async () => {
    let finishAdmission
    mocks.createAgentTurn.mockReturnValue(new Promise(resolve => {
      finishAdmission = resolve
    }))
    mountAgent()
    await flushPromises()
    await wrapper.get('[data-test="agent-input"]').setValue('只发送一次')

    const sendButton = wrapper.get('[data-test="send-agent-message"]')
    await sendButton.trigger('click')
    await wrapper.get('[data-test="agent-input"]').setValue('第二个问题')
    await sendButton.trigger('click')

    expect(mocks.createAgentTurn).toHaveBeenCalledOnce()
    finishAdmission({ turnId: 9, state: 'SUCCEEDED', temporary: false })
    await flushPromises()
  })

  it('replays from the last event id once when a non-terminal stream ends early', async () => {
    vi.useFakeTimers()
    try {
    mocks.createAgentTurn.mockResolvedValue({ turnId: 9, state: 'RUNNING', temporary: false })
    mocks.getAgentTurn.mockResolvedValue({
      turnId: 9,
      state: 'RUNNING',
      temporary: false,
      userMessage: '断线后继续',
      finalMessage: null,
      partialMessage: null,
    })
    mocks.streamAgentTurnEvents
      .mockImplementationOnce(async (_turnId, options) => {
        options.onEvent({
          id: '2-0',
          type: 'generating',
          data: { payload: { phase: 'grounded_answer' } },
        })
      })
      .mockImplementationOnce(async (_turnId, options) => {
        options.onEvent({
          id: '3-0',
          type: 'done',
          data: { payload: { finalMessage: '恢复后的完整回答' } },
        })
      })
    mountAgent()
    await flushPromises()
    await wrapper.get('[data-test="agent-input"]').setValue('断线后继续')
    await wrapper.get('[data-test="send-agent-message"]').trigger('click')
    await flushPromises()
    await vi.runAllTimersAsync()
    await flushPromises()

    expect(mocks.streamAgentTurnEvents).toHaveBeenCalledTimes(2)
    expect(mocks.streamAgentTurnEvents.mock.calls[1][1]).toEqual(expect.objectContaining({
      after: '2-0',
    }))
    expect(wrapper.text()).toContain('恢复后的完整回答')
    } finally {
      vi.useRealTimers()
    }
  })

  it('restores a persistent running turn after refresh without storing its message content', async () => {
    mocks.getAgentTurn.mockResolvedValue({
      turnId: 12,
      state: 'RUNNING',
      temporary: false,
      userMessage: '刷新前的普通问题',
      finalMessage: null,
      partialMessage: null,
    })
    mocks.streamAgentTurnEvents.mockImplementation(async (_turnId, options) => {
      options.onEvent({
        id: '5-0',
        type: 'done',
        data: { payload: { finalMessage: '刷新后恢复的回答' } },
      })
    })
    sessionStorage.setItem('metro.agent.persistent.turn', '12')

    mountAgent()
    await flushPromises()

    expect(mocks.getAgentTurn).toHaveBeenCalledWith(12)
    expect(mocks.streamAgentTurnEvents).toHaveBeenCalledWith(12, expect.objectContaining({
      after: null,
    }))
    expect(wrapper.text()).toContain('刷新前的普通问题')
    expect(wrapper.text()).toContain('刷新后恢复的回答')
    expect(sessionStorage.getItem('metro.agent.persistent.turn')).toBeNull()
    expect(storageValues(sessionStorage).join('\n')).not.toContain('刷新前的普通问题')
  })

  it('continues snapshot recovery after two stream interruptions until the turn becomes terminal', async () => {
    vi.useFakeTimers()
    try {
      mocks.createAgentTurn.mockResolvedValue({ turnId: 14, state: 'RUNNING', temporary: false })
      mocks.streamAgentTurnEvents
        .mockRejectedValueOnce(new TypeError('第一次断线'))
        .mockRejectedValueOnce(new TypeError('第二次断线'))
      mocks.getAgentTurn
        .mockResolvedValueOnce({
          turnId: 14,
          state: 'RUNNING',
          temporary: false,
          userMessage: '连续断线',
          finalMessage: null,
          partialMessage: null,
        })
        .mockResolvedValueOnce({
          turnId: 14,
          state: 'SUCCEEDED',
          temporary: false,
          userMessage: '连续断线',
          finalMessage: '最终快照已成功',
          partialMessage: null,
        })
      mountAgent()
      await flushPromises()
      await wrapper.get('[data-test="agent-input"]').setValue('连续断线')
      await wrapper.get('[data-test="send-agent-message"]').trigger('click')
      await flushPromises()

      await vi.runAllTimersAsync()
      await flushPromises()

      expect(mocks.streamAgentTurnEvents).toHaveBeenCalledTimes(2)
      expect(mocks.getAgentTurn).toHaveBeenCalledTimes(2)
      expect(wrapper.text()).toContain('最终快照已成功')
      expect(wrapper.find('.answer-progress').exists()).toBe(false)
    } finally {
      vi.useRealTimers()
    }
  })

  it('restores a failed temporary turn with a terminal explanation instead of a loading animation', async () => {
    mocks.getTemporarySession.mockResolvedValue({
      sessionId: 'session-1',
      createdAt: '2026-08-12T10:00:00Z',
      expiresAt: '2026-08-13T10:00:00Z',
    })
    mocks.getAgentTurn.mockResolvedValue({
      turnId: -8,
      state: 'FAILED',
      temporary: true,
      userMessage: '这次为什么失败？',
      finalMessage: null,
      partialMessage: null,
      error: 'AGENT_PROVIDER_FAILED',
    })
    sessionStorage.setItem('metro.agent.temporary.session', 'session-1')
    sessionStorage.setItem('metro.agent.temporary.turn', '-8')

    mountAgent()
    await flushPromises()

    expect(wrapper.text()).toContain('这次为什么失败？')
    expect(wrapper.text()).toContain('这次回答没有完成，请稍后重试。')
    expect(wrapper.find('.answer-progress').exists()).toBe(false)
  })

  it('deletes the backend session and clears all temporary metadata when leaving', async () => {
    mocks.getTemporarySession.mockResolvedValue({
      sessionId: 'session-1',
      createdAt: '2026-08-12T10:00:00Z',
      expiresAt: '2026-08-13T10:00:00Z',
    })
    sessionStorage.setItem('metro.agent.temporary.session', 'session-1')
    sessionStorage.setItem('metro.agent.temporary.turn', '-8')
    mountAgent()
    await flushPromises()

    await wrapper.get('[data-test="leave-temporary"]').trigger('click')
    await flushPromises()

    expect(mocks.deleteTemporarySession).toHaveBeenCalledOnce()
    expect(sessionStorage.getItem('metro.agent.temporary.session')).toBeNull()
    expect(sessionStorage.getItem('metro.agent.temporary.turn')).toBeNull()
    expect(wrapper.text()).not.toContain('临时对话已开启')
  })
})
