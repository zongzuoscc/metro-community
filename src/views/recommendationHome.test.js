// @vitest-environment jsdom

import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Home from './Home.vue'

const mocks = vi.hoisted(() => ({
  requestGet: vi.fn(),
  routerPush: vi.fn(),
  getHotRank: vi.fn(),
  getDraftCount: vi.fn(),
  getHotFeed: vi.fn(),
  getFollowFeed: vi.fn(),
  searchArticles: vi.fn(),
  searchUsers: vi.fn()
}))

vi.mock('../utils/request', () => ({
  default: { get: mocks.requestGet }
}))

vi.mock('../api/article', () => ({
  getHotRank: mocks.getHotRank,
  getDraftCount: mocks.getDraftCount,
  getHotFeed: mocks.getHotFeed,
  getFollowFeed: mocks.getFollowFeed,
  searchArticles: mocks.searchArticles
}))

vi.mock('../api/user', () => ({ searchUsers: mocks.searchUsers }))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mocks.routerPush }),
  useRoute: () => ({ query: {} })
}))

vi.mock('element-plus', () => ({
  ElMessage: { warning: vi.fn() },
  ElMessageBox: { confirm: vi.fn() },
  ElNotification: vi.fn()
}))

const slotStub = { template: '<div><slot /><slot name="dropdown" /></div>' }
const buttonStub = { template: '<button @click="$emit(\'click\')"><slot /></button>' }
const cardStub = { template: '<article><slot /></article>' }
const inputStub = {
  props: ['modelValue', 'placeholder'],
  emits: ['update:modelValue'],
  template: '<input :value="modelValue" :placeholder="placeholder" @input="$emit(\'update:modelValue\', $event.target.value)" />'
}

let wrapper
let infiniteLoader
let recommendationResponses
let chronologicalResponses
let recommendationCursors
let latestCursors

const mountHome = () => {
  const infiniteScroll = {
    mounted: (_element, binding) => { infiniteLoader = binding.value },
    updated: (_element, binding) => { infiniteLoader = binding.value }
  }
  wrapper = mount(Home, {
    global: {
      stubs: {
        ElAvatar: slotStub,
        ElBadge: slotStub,
        ElButton: buttonStub,
        ElCard: cardStub,
        ElDropdown: slotStub,
        ElDropdownItem: slotStub,
        ElDropdownMenu: slotStub,
        ElEmpty: slotStub,
        ElIcon: slotStub,
        ElInput: inputStub,
        ElSkeleton: slotStub
      },
      directives: {
        infiniteScroll,
        'infinite-scroll': infiniteScroll
      }
    }
  })
  return wrapper
}

beforeEach(() => {
  localStorage.clear()
  mocks.routerPush.mockReset()
  recommendationResponses = []
  chronologicalResponses = []
  recommendationCursors = []
  latestCursors = []
  infiniteLoader = undefined

  mocks.getHotRank.mockReset().mockResolvedValue({ code: 200, data: [] })
  mocks.getDraftCount.mockReset().mockResolvedValue({ code: 200, data: 0 })
  mocks.getHotFeed.mockReset().mockResolvedValue({ code: 200, data: [] })
  mocks.getFollowFeed.mockReset().mockResolvedValue({ code: 200, data: [] })
  mocks.searchArticles.mockReset().mockResolvedValue({ code: 200, data: { records: [] } })
  mocks.searchUsers.mockReset().mockResolvedValue({ code: 200, data: { records: [] } })
  mocks.requestGet.mockReset().mockImplementation((url, config = {}) => {
    if (url === '/api/recommendations/feed') {
      recommendationCursors.push(config.params?.cursor)
      return Promise.resolve(recommendationResponses.shift())
        .then((data) => ({ code: 200, data }))
    }
    if (url === '/api/article/feed') {
      latestCursors.push(config.params?.lastCreateTime)
      return Promise.resolve({ code: 200, data: chronologicalResponses.shift() || [] })
    }
    if (url === '/api/chat/friends') return Promise.resolve({ code: 200, data: [] })
    return Promise.resolve({ code: 200, data: 0 })
  })
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  localStorage.clear()
})

describe('Home recommendation and latest feeds', () => {
  it('does not render an empty loading panel after an empty feed settles', async () => {
    chronologicalResponses.push([])

    mountHome()
    await flushPromises()

    expect(wrapper.find('.loading-state').exists()).toBe(false)
  })

  it('renders untrusted feed text without creating executable HTML elements', async () => {
    chronologicalResponses.push([{
      id: 30,
      title: '<img src=x onerror=alert(1)>',
      summary: '<script>alert(2)</script>',
      createTime: '2026-08-09T12:00:00'
    }])

    mountHome()
    await flushPromises()

    expect(wrapper.get('.title').text()).toBe('<img src=x onerror=alert(1)>')
    expect(wrapper.find('.title img').exists()).toBe(false)
    expect(wrapper.find('.text-summary script').exists()).toBe(false)
  })

  it('keeps mobile recommendation, latest, hot, follow, and search controls reachable', async () => {
    chronologicalResponses.push(
      [{ id: 31, title: '移动推荐流', createTime: '2026-08-09T12:00:00' }],
      [{ id: 32, title: '移动最新流', createTime: '2026-08-09T11:00:00' }]
    )
    mountHome()
    await flushPromises()

    const mobileTabs = wrapper.findAll('.mobile-feed-tab')
    expect(mobileTabs.map((tab) => tab.text())).toEqual(['推荐', '最新', '热榜', '关注'])
    expect(wrapper.find('.mobile-feed-search').exists()).toBe(true)

    await mobileTabs[1].trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('移动最新流')
    expect(wrapper.text()).not.toContain('移动推荐流')

    mocks.searchArticles.mockResolvedValueOnce({
      code: 200,
      data: { records: [{ id: 33, title: '移动搜索结果' }] }
    })
    const mobileSearch = wrapper.get('.mobile-feed-search')
    await mobileSearch.setValue('移动关键词')
    await mobileSearch.trigger('keydown', { key: 'Enter' })
    await flushPromises()

    expect(mocks.searchArticles).toHaveBeenCalledWith('移动关键词', 1)
    expect(wrapper.text()).toContain('移动搜索结果')
  })

  it('uses independent cursors, renders personalized reasons, and routes only recommendation exposure IDs', async () => {
    localStorage.setItem('user', JSON.stringify({ id: 1001, username: 'tester' }))
    localStorage.setItem('token', 'real-local-token')
    recommendationResponses.push(
      {
        mode: 'PERSONALIZED',
        nextCursor: 'opaque-next',
        items: [{
          article: { id: 1, title: 'Redis 实战', createTime: '2026-08-09T10:00:00' },
          reason: '因为你常看 Redis',
          source: 'TAG',
          exposureId: 42
        }]
      },
      {
        mode: 'PERSONALIZED',
        nextCursor: null,
        items: [{ article: { id: 2, title: 'Java 21' }, reason: '关注作者的新文章', exposureId: 43 }]
      }
    )
    chronologicalResponses.push(
      [{ id: 3, title: '最新文章', createTime: '2026-08-09T09:00:00' }],
      []
    )

    mountHome()
    await flushPromises()

    expect(recommendationCursors).toEqual([undefined])
    expect(wrapper.text()).toContain('因为你常看 Redis')
    await wrapper.get('.feed-card').trigger('click')
    expect(mocks.routerPush).toHaveBeenLastCalledWith({
      path: '/article/1',
      query: { exposureId: '42' }
    })

    await infiniteLoader()
    await flushPromises()
    expect(recommendationCursors).toEqual([undefined, 'opaque-next'])

    const latestTab = wrapper.findAll('.nav-item').find((node) => node.text() === '最新')
    await latestTab.trigger('click')
    await flushPromises()
    expect(latestCursors).toEqual([undefined])
    expect(wrapper.text()).toContain('最新文章')
    expect(wrapper.text()).not.toContain('因为你常看 Redis')

    await wrapper.get('.feed-card').trigger('click')
    expect(mocks.routerPush).toHaveBeenLastCalledWith('/article/3')
    await infiniteLoader()
    await flushPromises()
    expect(latestCursors).toEqual([undefined, '2026-08-09T09:00:00'])
    expect(recommendationCursors).toEqual([undefined, 'opaque-next'])
  })

  it('keeps guests on chronology and hides reasons for non-personalized modes', async () => {
    chronologicalResponses.push([{ id: 5, title: '访客时间流', createTime: '2026-08-09T08:00:00' }])
    mountHome()
    await flushPromises()

    expect(recommendationCursors).toEqual([])
    expect(latestCursors).toEqual([undefined])
    expect(wrapper.text()).toContain('访客时间流')

    wrapper.unmount()
    wrapper = undefined
    localStorage.setItem('user', JSON.stringify({ id: 1001, username: 'tester' }))
    localStorage.setItem('token', 'real-local-token')
    recommendationResponses.push({
      mode: 'COLD_START',
      nextCursor: null,
      items: [{ article: { id: 6, title: '冷启动文章' }, reason: '不应展示的原因', exposureId: 99 }]
    })
    mountHome()
    await flushPromises()

    expect(wrapper.text()).toContain('冷启动文章')
    expect(wrapper.text()).not.toContain('不应展示的原因')
  })

  it('ignores a recommendation response that arrives after switching to latest', async () => {
    let resolveRecommendation
    const delayedRecommendation = new Promise((resolve) => { resolveRecommendation = resolve })
    localStorage.setItem('user', JSON.stringify({ id: 1001, username: 'tester' }))
    localStorage.setItem('token', 'real-local-token')
    recommendationResponses.push(delayedRecommendation)
    chronologicalResponses.push([
      { id: 8, title: '当前最新文章', createTime: '2026-08-09T11:00:00' }
    ])

    mountHome()
    const latestTab = wrapper.findAll('.nav-item').find((node) => node.text() === '最新')
    await latestTab.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('当前最新文章')

    resolveRecommendation({
      mode: 'PERSONALIZED',
      nextCursor: 'stale-cursor',
      items: [{
        article: { id: 7, title: '迟到的推荐文章' },
        reason: '不应污染最新流',
        exposureId: 70
      }]
    })
    await flushPromises()

    expect(wrapper.text()).toContain('当前最新文章')
    expect(wrapper.text()).not.toContain('迟到的推荐文章')
    expect(wrapper.text()).not.toContain('不应污染最新流')
  })
})
