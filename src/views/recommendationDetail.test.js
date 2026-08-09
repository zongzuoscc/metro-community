// @vitest-environment jsdom

import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ArticleDetail from './ArticleDetail.vue'

const mocks = vi.hoisted(() => ({
  route: null,
  routerPush: vi.fn(),
  requestGet: vi.fn(),
  requestPost: vi.fn(),
  requestDelete: vi.fn(),
  getArticleDetail: vi.fn(),
  deleteArticle: vi.fn(),
  getSimilarArticles: vi.fn(),
  getAiSummary: vi.fn(),
  getCommentList: vi.fn(),
  publishComment: vi.fn(),
  deleteComment: vi.fn(),
  submitReport: vi.fn(),
  reportQualifiedView: vi.fn(),
  createTracker: vi.fn(),
  tracker: {
    start: vi.fn(),
    reset: vi.fn(),
    dispose: vi.fn()
  }
}))

vi.mock('vue-router', async () => {
  const { reactive } = await import('vue')
  mocks.route = reactive({ params: { id: '1' }, query: {}, path: '/article/1' })
  return {
    useRoute: () => mocks.route,
    useRouter: () => ({ push: mocks.routerPush })
  }
})

vi.mock('../utils/request', () => ({
  default: {
    get: mocks.requestGet,
    post: mocks.requestPost,
    delete: mocks.requestDelete
  }
}))

vi.mock('../api/article', () => ({
  getArticleDetail: mocks.getArticleDetail,
  deleteArticle: mocks.deleteArticle,
  getSimilarArticles: mocks.getSimilarArticles,
  getAiSummary: mocks.getAiSummary
}))

vi.mock('../api/comment', () => ({
  getCommentList: mocks.getCommentList,
  publishComment: mocks.publishComment,
  deleteComment: mocks.deleteComment
}))

vi.mock('../api/report', () => ({ submitReport: mocks.submitReport }))
vi.mock('../api/recommendation', () => ({ reportQualifiedView: mocks.reportQualifiedView }))
vi.mock('../utils/qualifiedArticleView', () => ({
  createQualifiedArticleView: mocks.createTracker
}))

vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn(), success: vi.fn(), warning: vi.fn() },
  ElMessageBox: { confirm: vi.fn() }
}))

const slotStub = { template: '<div><slot /><slot name="reference" /><slot name="footer" /><slot name="dropdown" /></div>' }
const buttonStub = { template: '<button @click="$emit(\'click\')"><slot /></button>' }

let wrapper

const deferred = () => {
  let resolve
  let reject
  const promise = new Promise((nextResolve, nextReject) => {
    resolve = nextResolve
    reject = nextReject
  })
  return { promise, resolve, reject }
}

const mountDetail = () => {
  wrapper = mount(ArticleDetail, {
    global: {
      stubs: {
        ElAvatar: slotStub,
        ElButton: buttonStub,
        ElDialog: slotStub,
        ElDropdown: slotStub,
        ElDropdownItem: slotStub,
        ElDropdownMenu: slotStub,
        ElEmpty: slotStub,
        ElIcon: slotStub,
        ElInput: { template: '<textarea />' },
        ElPopconfirm: slotStub,
        ElRadio: slotStub,
        ElRadioGroup: slotStub,
        ElTag: slotStub,
        VMdPreview: { props: ['text'], template: '<div class="markdown-preview">{{ text }}</div>' }
      },
      directives: { loading: {} }
    }
  })
  return wrapper
}

beforeEach(() => {
  localStorage.clear()
  localStorage.setItem('user', JSON.stringify({ id: 1001, username: 'tester' }))
  localStorage.setItem('token', 'real-local-token')
  Object.assign(mocks.route.params, { id: '1' })
  Object.assign(mocks.route.query, {})
  for (const key of Object.keys(mocks.route.query)) delete mocks.route.query[key]
  mocks.route.path = '/article/1'

  for (const fn of [
    mocks.routerPush, mocks.requestGet, mocks.requestPost, mocks.requestDelete,
    mocks.getArticleDetail, mocks.deleteArticle, mocks.getSimilarArticles,
    mocks.getAiSummary, mocks.getCommentList, mocks.publishComment,
    mocks.deleteComment, mocks.submitReport, mocks.reportQualifiedView,
    mocks.createTracker, mocks.tracker.start, mocks.tracker.reset, mocks.tracker.dispose
  ]) fn.mockReset()

  mocks.createTracker.mockReturnValue(mocks.tracker)
  mocks.getSimilarArticles.mockResolvedValue({ code: 200, data: [] })
  mocks.getCommentList.mockResolvedValue({ code: 200, data: [] })
  mocks.requestGet.mockResolvedValue({ code: 200, data: false })
  mocks.requestPost.mockResolvedValue({ code: 200, data: null })
  mocks.requestDelete.mockResolvedValue({ code: 200, data: null })
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  localStorage.clear()
})

describe('ArticleDetail qualified-view lifecycle', () => {
  it('starts with a validated recommendation exposure and disposes on unmount', async () => {
    mocks.route.query.exposureId = '42'
    mocks.getArticleDetail.mockResolvedValue({
      code: 200,
      data: { id: 1, title: '推荐文章', content: '正文', authorId: 2001 }
    })

    mountDetail()
    await flushPromises()

    expect(mocks.tracker.start).toHaveBeenCalledWith(1, 42)
    wrapper.unmount()
    wrapper = undefined
    expect(mocks.tracker.dispose).toHaveBeenCalledTimes(1)
  })

  it('ignores a stale detail response and never starts its exposure timer', async () => {
    const articleA = deferred()
    const articleB = deferred()
    mocks.route.query.exposureId = '42'
    mocks.getArticleDetail.mockImplementation((id) => id === '1' ? articleA.promise : articleB.promise)

    mountDetail()
    mocks.route.params.id = '2'
    mocks.route.path = '/article/2'
    delete mocks.route.query.exposureId
    await nextTick()

    articleB.resolve({
      code: 200,
      data: { id: 2, title: '文章 B', content: 'B 正文', authorId: 2002 }
    })
    await flushPromises()
    expect(mocks.tracker.start).toHaveBeenCalledWith(2, undefined)
    expect(wrapper.text()).toContain('文章 B')

    articleA.resolve({
      code: 200,
      data: { id: 1, title: '迟到的文章 A', content: 'A 正文', authorId: 2001 }
    })
    await flushPromises()

    expect(mocks.tracker.start).toHaveBeenCalledTimes(1)
    expect(mocks.tracker.reset).not.toHaveBeenCalledWith(1, 42)
    expect(wrapper.text()).toContain('文章 B')
    expect(wrapper.text()).not.toContain('迟到的文章 A')
  })
})
