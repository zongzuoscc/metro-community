// @vitest-environment jsdom

import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  summarizeArticle: vi.fn(),
  analyzeArticle: vi.fn(),
  createWritingSuggestion: vi.fn(),
  createAgentTurn: vi.fn(),
  streamAgentTurnEvents: vi.fn(),
  getTemporarySession: vi.fn(),
  createTemporarySession: vi.fn(),
  deleteTemporarySession: vi.fn(),
  getAgentMemories: vi.fn(),
  getAgentMemorySetting: vi.fn(),
  getAgentWebSearchSetting: vi.fn(),
  updateAgentWebSearchSetting: vi.fn(),
  getAgentTurnHistory: vi.fn(),
  getAgentTurn: vi.fn(),
}))

vi.mock('../api/agent', () => ({
  summarizeArticle: mocks.summarizeArticle,
  analyzeArticle: mocks.analyzeArticle,
  createWritingSuggestion: mocks.createWritingSuggestion,
  createAgentTurn: mocks.createAgentTurn,
  streamAgentTurnEvents: mocks.streamAgentTurnEvents,
  getTemporarySession: mocks.getTemporarySession,
  createTemporarySession: mocks.createTemporarySession,
  deleteTemporarySession: mocks.deleteTemporarySession,
  getAgentMemories: mocks.getAgentMemories,
  getAgentMemorySetting: mocks.getAgentMemorySetting,
  getAgentWebSearchSetting: mocks.getAgentWebSearchSetting,
  updateAgentWebSearchSetting: mocks.updateAgentWebSearchSetting,
  getAgentTurnHistory: mocks.getAgentTurnHistory,
  getAgentTurn: mocks.getAgentTurn,
  cancelAgentTurn: vi.fn(),
}))

const { default: AgentDock } = await import('./AgentDock.vue')
const { clearAgentPageContext, setAgentPageContext } = await import('../composables/useAgentPageContext')

beforeEach(() => {
  localStorage.clear()
  localStorage.setItem('token', 'frontend-test-token')
  clearAgentPageContext()
  Object.values(mocks).forEach(mock => mock.mockReset())
  mocks.getTemporarySession.mockRejectedValue({ response: { status: 404 } })
  mocks.getAgentMemories.mockResolvedValue([])
  mocks.getAgentMemorySetting.mockResolvedValue({ enabled: true, version: 0 })
  mocks.getAgentWebSearchSetting.mockResolvedValue({ enabled: true })
  mocks.updateAgentWebSearchSetting.mockImplementation(enabled => Promise.resolve({ enabled }))
  mocks.getAgentTurnHistory.mockResolvedValue({ items: [], nextBeforeTurnId: null })
})

function mountDock() {
  return mount(AgentDock, {
    attachTo: document.body,
    global: {
      stubs: {
        ElIcon: { template: '<span><slot /></span>' },
      },
    },
  })
}

function pointerEvent(type, values) {
  const event = new MouseEvent(type, {
    clientX: values.clientX,
    clientY: values.clientY,
    button: values.button ?? 0,
  })
  Object.defineProperty(event, 'pointerId', { value: values.pointerId })
  return event
}

describe('全局 Agent 桌宠小窗', () => {
  it('默认显示桌宠，点击后打开小窗并可切换全屏', async () => {
    const wrapper = mountDock()

    expect(wrapper.get('[data-test="agent-pet"]').isVisible()).toBe(true)
    expect(wrapper.find('[data-test="agent-window"]').exists()).toBe(false)

    await wrapper.get('[data-test="agent-pet"]').trigger('click')
    expect(wrapper.get('[data-test="agent-window"]').isVisible()).toBe(true)

    await wrapper.get('[data-test="agent-expand"]').trigger('click')
    expect(wrapper.get('[data-test="agent-dock"]').classes()).toContain('is-fullscreen')
  })

  it('普通小窗打开时显示主对话历史，但不显示全屏历史轨道', async () => {
    mocks.getAgentTurnHistory.mockResolvedValue({
      items: [{
        turnId: 72,
        questionPreview: '长期记忆是怎么实现的',
        answerPreview: '长期记忆会保存稳定偏好。',
        userMessage: '长期记忆是怎么实现的？',
        finalMessage: '长期记忆会保存稳定偏好，并在回答前按需召回。',
        createdAt: '2026-08-13T08:50:00',
      }],
      nextBeforeTurnId: null,
    })
    const wrapper = mountDock()

    await wrapper.get('[data-test="agent-pet"]').trigger('click')
    await flushPromises()

    expect(mocks.getAgentTurnHistory).toHaveBeenCalledWith({ size: 30 })
    expect(wrapper.find('[data-test="agent-history-rail"]').exists()).toBe(false)
    expect(wrapper.get('[data-test="agent-conversation"]').text())
      .toContain('长期记忆会保存稳定偏好，并在回答前按需召回。')
  })

  it('普通小窗首次加载历史后定位到最新一轮，而不是停在最早记录', async () => {
    let finishHistory
    mocks.getAgentTurnHistory.mockImplementation(() => new Promise(resolve => {
      finishHistory = resolve
    }))
    const wrapper = mountDock()

    await wrapper.get('[data-test="agent-pet"]').trigger('click')
    const conversation = wrapper.get('[data-test="agent-conversation"]').element
    Object.defineProperty(conversation, 'scrollHeight', { configurable: true, value: 960 })
    finishHistory({
      items: [{
        turnId: 80, questionPreview: '最新问题', answerPreview: '最新回答',
        userMessage: '最新问题', finalMessage: '最新回答', createdAt: '2026-08-13T10:00:00',
      }],
      nextBeforeTurnId: null,
    })
    await flushPromises()

    expect(conversation.scrollTop).toBe(960)
  })

  it('滚动到主对话顶部时自动加载更早记录，并保持当前阅读位置不跳动', async () => {
    let finishOlderPage
    mocks.getAgentTurnHistory
      .mockResolvedValueOnce({
        items: [{
          turnId: 80, questionPreview: '较新问题', answerPreview: '较新回答',
          userMessage: '较新问题', finalMessage: '较新回答', createdAt: '2026-08-13T10:00:00',
        }],
        nextBeforeTurnId: 80,
      })
      .mockImplementationOnce(() => new Promise(resolve => { finishOlderPage = resolve }))
    const wrapper = mountDock()
    await wrapper.get('[data-test="agent-pet"]').trigger('click')
    await flushPromises()
    const conversationWrapper = wrapper.get('[data-test="agent-conversation"]')
    const conversation = conversationWrapper.element
    let scrollHeight = 600
    Object.defineProperty(conversation, 'scrollHeight', {
      configurable: true,
      get: () => scrollHeight,
    })
    conversation.scrollTop = 0

    await conversationWrapper.trigger('scroll')
    await flushPromises()
    expect(mocks.getAgentTurnHistory).toHaveBeenLastCalledWith({ beforeTurnId: 80, size: 30 })
    scrollHeight = 900
    finishOlderPage({
      items: [{
        turnId: 79, questionPreview: '更早问题', answerPreview: '更早回答',
        userMessage: '更早问题', finalMessage: '更早回答', createdAt: '2026-08-13T09:00:00',
      }],
      nextBeforeTurnId: null,
    })
    await flushPromises()

    expect(conversation.scrollTop).toBe(300)
    expect(conversationWrapper.text()).toContain('更早问题')
  })

  it('主对话历史恢复站内引用和联网来源的可点击链接', async () => {
    mocks.getAgentTurnHistory.mockResolvedValue({
      items: [{
        turnId: 81, questionPreview: '资料来自哪里', answerPreview: '参考如下',
        userMessage: '资料来自哪里？', finalMessage: '站内结论。[1] 联网补充。[W1]',
        citations: [{ marker: 1, title: '站内文章', url: '/article/42' }],
        webSources: [{ index: 1, title: '外部报道', url: 'https://example.com/report', siteName: '示例站' }],
        webSourcesExpired: true,
        createdAt: '2026-08-13T10:10:00',
      }],
      nextBeforeTurnId: null,
    })
    const wrapper = mountDock()

    await wrapper.get('[data-test="agent-pet"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('a[href="/article/42"]').text()).toContain('站内文章')
    expect(wrapper.get('a[href="https://example.com/report"]').text()).toContain('外部报道')
    expect(wrapper.text()).toContain('较早的联网来源快照已超过 30 天保留期')
  })

  it('新回答进入权威历史后只显示一次，不与本地即时消息重复', async () => {
    mocks.getAgentTurnHistory
      .mockResolvedValueOnce({
        items: [{
          turnId: 90, questionPreview: '原有问题', answerPreview: '原有回答',
          userMessage: '原有问题', finalMessage: '原有回答', createdAt: '2026-08-13T10:10:00',
          citations: [], webSources: [],
        }],
        nextBeforeTurnId: null,
      })
      .mockResolvedValueOnce({
        items: [
          {
            turnId: 91, questionPreview: '唯一问题', answerPreview: '唯一回答',
            userMessage: '唯一问题', finalMessage: '唯一回答', createdAt: '2026-08-13T10:20:00',
            citations: [], webSources: [],
          },
          {
            turnId: 90, questionPreview: '原有问题', answerPreview: '原有回答',
            userMessage: '原有问题', finalMessage: '原有回答', createdAt: '2026-08-13T10:10:00',
            citations: [], webSources: [],
          },
        ],
        nextBeforeTurnId: null,
      })
    mocks.createAgentTurn.mockResolvedValue({ turnId: 91 })
    mocks.streamAgentTurnEvents.mockImplementation(async (_turnId, options) => {
      options.onEvent({ type: 'done', data: { payload: { finalMessage: '唯一回答' } } })
    })
    const wrapper = mountDock()
    await wrapper.get('[data-test="agent-pet"]').trigger('click')
    await flushPromises()
    await wrapper.get('textarea').setValue('唯一问题')
    await wrapper.get('[aria-label="发送"]').trigger('click')
    await flushPromises()

    // 小窗完成新回答后立即刷新连续主对话，旧记录不能先消失、等下次打开才恢复。
    expect(wrapper.get('[data-test="agent-conversation"]').text()).toContain('原有回答')
    expect(wrapper.get('[data-test="agent-conversation"]').text().match(/唯一回答/g)).toHaveLength(1)
  })

  it('首屏历史仍在加载时完成新回答，会在首屏结束后补做一次权威刷新', async () => {
    let finishInitialHistory
    mocks.getAgentTurnHistory
      .mockImplementationOnce(() => new Promise(resolve => { finishInitialHistory = resolve }))
      .mockResolvedValueOnce({
        items: [{
          turnId: 101, questionPreview: '竞态问题', answerPreview: '权威回答',
          userMessage: '竞态问题', finalMessage: '权威回答', createdAt: '2026-08-13T10:30:00',
          citations: [], webSources: [],
        }],
        nextBeforeTurnId: null,
      })
    mocks.createAgentTurn.mockResolvedValue({ turnId: 101 })
    mocks.streamAgentTurnEvents.mockImplementation(async (_turnId, options) => {
      options.onEvent({ type: 'done', data: { payload: { finalMessage: '权威回答' } } })
    })
    const wrapper = mountDock()
    await wrapper.get('[data-test="agent-pet"]').trigger('click')
    await wrapper.get('textarea').setValue('竞态问题')
    await wrapper.get('[aria-label="发送"]').trigger('click')
    await flushPromises()

    finishInitialHistory({ items: [], nextBeforeTurnId: null })
    await flushPromises()

    expect(mocks.getAgentTurnHistory).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[data-test="agent-conversation"]').text().match(/权威回答/g)).toHaveLength(1)
  })

  it('刷新最新问答时保留已经向上加载的更早记录和原分页游标', async () => {
    mocks.getAgentTurnHistory
      .mockResolvedValueOnce({
        items: [{ turnId: 60, userMessage: '第 60 问', finalMessage: '第 60 答' }],
        nextBeforeTurnId: 60,
      })
      .mockResolvedValueOnce({
        items: [{ turnId: 30, userMessage: '第 30 问', finalMessage: '第 30 答' }],
        nextBeforeTurnId: 30,
      })
      .mockResolvedValueOnce({
        items: [
          { turnId: 61, userMessage: '第 61 问', finalMessage: '第 61 答' },
          { turnId: 60, userMessage: '第 60 问', finalMessage: '第 60 答' },
        ],
        nextBeforeTurnId: 60,
      })
    mocks.createAgentTurn.mockResolvedValue({ turnId: 61 })
    mocks.streamAgentTurnEvents.mockImplementation(async (_turnId, options) => {
      options.onEvent({ type: 'done', data: { payload: { finalMessage: '第 61 答' } } })
    })
    const wrapper = mountDock()
    await wrapper.get('[data-test="agent-pet"]').trigger('click')
    await flushPromises()
    const conversation = wrapper.get('[data-test="agent-conversation"]')
    conversation.element.scrollTop = 0
    await conversation.trigger('scroll')
    await flushPromises()
    expect(conversation.text()).toContain('第 30 答')

    await wrapper.get('textarea').setValue('第 61 问')
    await wrapper.get('[aria-label="发送"]').trigger('click')
    await flushPromises()

    expect(conversation.text()).toContain('第 30 答')
    expect(conversation.text().match(/第 61 答/g)).toHaveLength(1)
  })

  it('全屏展示连续主对话，点击历史轨道只定位而不隐藏其他问答', async () => {
    const scrollIntoView = vi.fn()
    Element.prototype.scrollIntoView = scrollIntoView
    mocks.getAgentTurnHistory.mockResolvedValue({
      items: [
        {
          turnId: 74,
          questionPreview: '联网搜索如何关闭',
          answerPreview: '可以在输入框上方关闭。',
          userMessage: '联网搜索如何关闭？',
          finalMessage: '可以在输入框上方关闭联网搜索。',
          createdAt: '2026-08-13T09:10:00',
        },
        {
          turnId: 73,
          questionPreview: '临时对话模式的意义是什么',
          answerPreview: '临时对话不保留历史和长期记忆。',
          userMessage: '临时对话模式的意义是什么？',
          finalMessage: '临时对话不保留历史和长期记忆。',
          createdAt: '2026-08-13T09:00:00',
        },
      ],
      nextBeforeTurnId: null,
    })
    const wrapper = mountDock()
    await wrapper.get('[data-test="agent-pet"]').trigger('click')
    expect(wrapper.find('[data-test="agent-history-rail"]').exists()).toBe(false)

    await wrapper.get('[data-test="agent-expand"]').trigger('click')
    await flushPromises()
    expect(mocks.getAgentTurnHistory).toHaveBeenCalledWith({ size: 30 })
    expect(wrapper.get('[data-test="agent-history-rail"]').text()).toContain('临时对话模式')
    expect(wrapper.get('[data-test="history-preview-73"]').text()).toContain('临时对话不保留历史')
    const conversationBeforeClick = wrapper.get('[data-test="agent-conversation"]').text()
    expect(conversationBeforeClick).toContain('临时对话模式的意义是什么？')
    expect(conversationBeforeClick).toContain('联网搜索如何关闭？')

    await wrapper.get('[data-test="history-turn-73"]').trigger('click')
    await flushPromises()
    expect(mocks.getAgentTurn).not.toHaveBeenCalled()
    expect(wrapper.get('[data-test="agent-conversation"]').text()).toContain('联网搜索如何关闭？')
    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'start' })
  })

  it('opens the memory center from normal chat and hides the entry in temporary mode', async () => {
    mocks.createTemporarySession.mockResolvedValue({ sessionId: 'temporary-memory-boundary' })
    const wrapper = mountDock()
    await wrapper.get('[data-test="agent-pet"]').trigger('click')

    await wrapper.get('[data-test="memory-center-toggle"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('你的长期记忆')
    expect(mocks.getAgentMemories).toHaveBeenCalledTimes(1)

    await wrapper.get('[data-test="memory-center-toggle"]').trigger('click')
    await wrapper.get('[data-test="temporary-toggle"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="memory-center-toggle"]').exists()).toBe(false)
  })

  it('同标签页退出登录后立即隐藏桌宠和已打开的对话内容', async () => {
    const wrapper = mountDock()
    await wrapper.get('[data-test="agent-pet"]').trigger('click')
    expect(wrapper.find('[data-test="agent-window"]').exists()).toBe(true)

    localStorage.clear()
    window.dispatchEvent(new Event('metro-auth-changed'))
    await wrapper.vm.$nextTick()

    expect(wrapper.find('[data-test="agent-dock"]').exists()).toBe(false)
  })

  it('换账号时清空上一个账号的消息，并忽略上一个账号迟到的请求', async () => {
    let finishSummary
    mocks.summarizeArticle.mockImplementation(() => new Promise(resolve => { finishSummary = resolve }))
    setAgentPageContext({ kind: 'article', articleId: 42, title: '账号 A 的文章' })
    const wrapper = mountDock()
    await wrapper.get('[data-test="agent-pet"]').trigger('click')
    await wrapper.get('[data-test="summarize-article"]').trigger('click')

    localStorage.removeItem('token')
    window.dispatchEvent(new StorageEvent('storage', { key: 'token', oldValue: 'account-a', newValue: null }))
    await wrapper.vm.$nextTick()
    localStorage.setItem('token', 'account-b')
    window.dispatchEvent(new Event('metro-auth-changed'))
    await wrapper.vm.$nextTick()

    finishSummary({ content: '账号 A 的迟到总结', fundingSource: 'PLATFORM' })
    await flushPromises()

    expect(wrapper.text()).not.toContain('账号 A 的迟到总结')
    expect(wrapper.text()).not.toContain('账号 A 的文章')
  })

  it('拖动标题栏时移动包含桌宠和小窗的同一容器', async () => {
    const wrapper = mountDock()
    await wrapper.get('[data-test="agent-pet"]').trigger('click')
    const dock = wrapper.get('[data-test="agent-dock"]')
    const before = dock.attributes('style')

    await wrapper.get('[data-test="agent-drag-handle"]').trigger('pointerdown', {
      pointerId: 7,
      clientX: 900,
      clientY: 500,
      button: 0,
    })
    window.dispatchEvent(pointerEvent('pointermove', {
      pointerId: 7,
      clientX: 720,
      clientY: 380,
    }))
    window.dispatchEvent(pointerEvent('pointerup', { pointerId: 7 }))
    await wrapper.vm.$nextTick()

    expect(dock.attributes('style')).not.toBe(before)
    expect(dock.find('[data-test="agent-pet"]').exists()).toBe(true)
    expect(dock.find('[data-test="agent-window"]').exists()).toBe(true)
  })

  it('小窗关闭时可直接拖动桌宠，且拖动结束不会误打开小窗', async () => {
    const wrapper = mountDock()
    const dock = wrapper.get('[data-test="agent-dock"]')
    const pet = wrapper.get('[data-test="agent-pet"]')
    const before = dock.attributes('style')

    await pet.trigger('pointerdown', {
      pointerId: 11,
      clientX: 960,
      clientY: 620,
      button: 0,
    })
    window.dispatchEvent(pointerEvent('pointermove', {
      pointerId: 11,
      clientX: 700,
      clientY: 360,
    }))
    window.dispatchEvent(pointerEvent('pointerup', {
      pointerId: 11,
      clientX: 700,
      clientY: 360,
    }))
    // 真实浏览器会在 pointerup 后继续派发 click，这个 click 必须被识别为拖动尾声。
    await pet.trigger('click')
    await wrapper.vm.$nextTick()

    expect(dock.attributes('style')).not.toBe(before)
    expect(wrapper.find('[data-test="agent-window"]').exists()).toBe(false)
  })

  it('在文章页展示总结动作，结果仍显示在小窗中', async () => {
    setAgentPageContext({ kind: 'article', articleId: 42, title: '只读文章' })
    mocks.summarizeArticle.mockResolvedValue({
      content: '这是文章总结',
      fundingSource: 'PLATFORM',
      provider: 'deepseek',
      model: 'deepseek-chat',
    })
    const wrapper = mountDock()
    await wrapper.get('[data-test="agent-pet"]').trigger('click')
    await wrapper.get('[data-test="summarize-article"]').trigger('click')
    await flushPromises()

    expect(mocks.summarizeArticle).toHaveBeenCalledWith(42)
    expect(wrapper.text()).toContain('这是文章总结')
    expect(wrapper.text()).toContain('平台基础额度')
  })

  it('写作建议拒绝时不改编辑器，只有确认后才应用', async () => {
    const applySuggestion = vi.fn()
    setAgentPageContext({
      kind: 'writing',
      title: '正在编写的文章',
      getWritingSnapshot: () => ({
        title: '正在编写的文章', content: '原始全文', selectedText: '原始选区',
        selectionFrom: 3, selectionTo: 9, documentVersion: 12,
      }),
      applyWritingSuggestion: applySuggestion,
    })
    mocks.createWritingSuggestion.mockResolvedValue({
      operation: 'POLISH', originalText: '原始选区', suggestedText: '润色后的选区',
      documentVersion: 12, selectionFrom: 3, selectionTo: 9,
      fundingSource: 'USER', provider: 'openai', model: 'gpt-4.1-mini',
    })
    const wrapper = mountDock()
    await wrapper.get('[data-test="agent-pet"]').trigger('click')
    await wrapper.get('[data-test="polish-writing"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('润色后的选区')
    await wrapper.get('[data-test="reject-suggestion"]').trigger('click')
    expect(applySuggestion).not.toHaveBeenCalled()

    await wrapper.get('[data-test="polish-writing"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="apply-suggestion"]').trigger('click')
    expect(applySuggestion).toHaveBeenCalledWith(expect.objectContaining({
      suggestedText: '润色后的选区', documentVersion: 12,
    }))
  })

  it('切换文章后丢弃上一篇文章迟到返回的写作建议', async () => {
    let finishSuggestion
    mocks.createWritingSuggestion.mockImplementation(() => new Promise(resolve => { finishSuggestion = resolve }))
    setAgentPageContext({
      kind: 'writing', documentKey: 'article:101', title: '文章 A',
      getWritingSnapshot: () => ({ content: 'A', selectedText: 'A', selectionFrom: 0, selectionTo: 1, documentVersion: 3 }),
      applyWritingSuggestion: vi.fn(),
    })
    const wrapper = mountDock()
    await wrapper.get('[data-test="agent-pet"]').trigger('click')
    await wrapper.get('[data-test="polish-writing"]').trigger('click')

    setAgentPageContext({
      kind: 'writing', documentKey: 'article:202', title: '文章 B',
      getWritingSnapshot: () => ({ content: 'B', selectedText: 'B', selectionFrom: 0, selectionTo: 1, documentVersion: 3 }),
      applyWritingSuggestion: vi.fn(),
    })
    finishSuggestion({
      originalText: 'A', suggestedText: '润色后的 A', selectionFrom: 0, selectionTo: 1, documentVersion: 3,
    })
    await flushPromises()

    expect(wrapper.find('[data-test="apply-suggestion"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('润色后的 A')
  })

  it('临时模式先创建服务端会话，并在发送 turn 时携带会话 ID', async () => {
    mocks.createTemporarySession.mockResolvedValue({ sessionId: 'temp-session-7' })
    mocks.createAgentTurn.mockResolvedValue({ turnId: -17 })
    mocks.streamAgentTurnEvents.mockImplementation(async (_turnId, options) => {
      options.onEvent({ type: 'done', data: { payload: {
        finalMessage: '临时回答', fundingSource: 'USER', provider: 'qwen', model: 'qwen-plus',
      } } })
    })
    const wrapper = mountDock()
    await wrapper.get('[data-test="agent-pet"]').trigger('click')

    await wrapper.get('[data-test="temporary-toggle"]').trigger('click')
    await flushPromises()
    await wrapper.get('textarea').setValue('这条消息不要持久保存')
    await wrapper.get('[aria-label="发送"]').trigger('click')
    await flushPromises()

    expect(mocks.createTemporarySession).toHaveBeenCalledTimes(1)
    expect(mocks.createAgentTurn).toHaveBeenCalledWith(expect.objectContaining({
      temporary: true,
      temporarySessionId: 'temp-session-7',
    }))
    expect(wrapper.text()).toContain('本次使用你的 qwen API')
  })

  it('主对话默认显示联网开关，关闭后持久保存并展示分组来源', async () => {
    mocks.createAgentTurn.mockResolvedValue({ turnId: 91 })
    mocks.streamAgentTurnEvents.mockImplementation(async (_turnId, options) => {
      options.onEvent({ type: 'done', data: { payload: {
        finalMessage: '【站内文章】站内结论。[1]\n\n【联网搜索】最新补充。[W1]',
        citations: [{ marker: 1, title: '站内文章', url: '/article/42' }],
        webSources: [{ index: 1, title: '外部来源', url: 'https://example.com/news', siteName: '示例站' }],
        fundingSource: 'PLATFORM',
      } } })
    })
    const wrapper = mountDock()
    await wrapper.get('[data-test="agent-pet"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test="web-search-toggle"]').attributes('aria-pressed')).toBe('true')
    await wrapper.get('[data-test="web-search-toggle"]').trigger('click')
    await flushPromises()
    expect(mocks.updateAgentWebSearchSetting).toHaveBeenCalledWith(false)

    await wrapper.get('textarea').setValue('测试来源展示')
    await wrapper.get('[aria-label="发送"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('站内资料')
    expect(wrapper.text()).toContain('联网来源')
    expect(wrapper.get('a[href="/article/42"]').text()).toContain('站内文章')
    expect(wrapper.get('a[href="https://example.com/news"]').text()).toContain('外部来源')
  })

  it('换账号后忽略上一个账号迟到创建的临时会话', async () => {
    let finishTemporarySession
    mocks.createTemporarySession.mockImplementation(() => new Promise(resolve => {
      finishTemporarySession = resolve
    }))
    const wrapper = mountDock()
    await wrapper.get('[data-test="agent-pet"]').trigger('click')
    await wrapper.get('[data-test="temporary-toggle"]').trigger('click')

    localStorage.removeItem('token')
    window.dispatchEvent(new Event('metro-auth-changed'))
    localStorage.setItem('token', 'account-b')
    window.dispatchEvent(new Event('metro-auth-changed'))
    finishTemporarySession({ sessionId: 'account-a-session' })
    await flushPromises()
    await wrapper.get('[data-test="agent-pet"]').trigger('click')

    expect(wrapper.text()).toContain('开启临时')
    expect(wrapper.text()).not.toContain('退出临时')
  })
})
