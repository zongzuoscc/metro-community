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

  it('只在全屏展示主对话历史轨道，点击摘要恢复该轮问答', async () => {
    mocks.getAgentTurnHistory.mockResolvedValue({
      items: [{
        turnId: 73,
        questionPreview: '临时对话模式的意义是什么',
        answerPreview: '临时对话不保留历史和长期记忆。',
        createdAt: '2026-08-13T09:00:00',
      }],
      nextBeforeTurnId: null,
    })
    mocks.getAgentTurn.mockResolvedValue({
      turnId: 73,
      userMessage: '临时对话模式的意义是什么',
      finalMessage: '临时对话不保留历史和长期记忆。',
      state: 'SUCCEEDED',
    })
    const wrapper = mountDock()
    await wrapper.get('[data-test="agent-pet"]').trigger('click')
    expect(wrapper.find('[data-test="agent-history-rail"]').exists()).toBe(false)

    await wrapper.get('[data-test="agent-expand"]').trigger('click')
    await flushPromises()
    expect(mocks.getAgentTurnHistory).toHaveBeenCalledWith({ size: 30 })
    expect(wrapper.get('[data-test="agent-history-rail"]').text()).toContain('临时对话模式')
    expect(wrapper.get('[data-test="history-preview-73"]').text()).toContain('临时对话不保留历史')

    await wrapper.get('[data-test="history-turn-73"]').trigger('click')
    await flushPromises()
    expect(mocks.getAgentTurn).toHaveBeenCalledWith(73)
    expect(wrapper.get('[data-test="agent-conversation"]').text()).toContain('临时对话不保留历史和长期记忆')
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
