// @vitest-environment jsdom

import { flushPromises, mount } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

function deferred() {
  let resolve
  let reject
  const promise = new Promise((nextResolve, nextReject) => {
    resolve = nextResolve
    reject = nextReject
  })
  return { promise, resolve, reject }
}

const mocks = vi.hoisted(() => ({
  route: null,
  routerPush: vi.fn(),
  routerGo: vi.fn(),
  leaveGuard: null,
  updateGuard: null,
  getArticleForEdit: vi.fn(),
  publishArticle: vi.fn(),
  saveDraft: vi.fn(),
  getHotTags: vi.fn(),
  messageError: vi.fn(),
  messageWarning: vi.fn(),
  messageSuccess: vi.fn(),
}))

mocks.route = reactive({ query: { id: '1' } })

vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => ({ push: mocks.routerPush, go: mocks.routerGo }),
  onBeforeRouteLeave: callback => { mocks.leaveGuard = callback },
  onBeforeRouteUpdate: callback => { mocks.updateGuard = callback },
}))

vi.mock('element-plus', () => ({
  ElMessage: {
    error: mocks.messageError,
    warning: mocks.messageWarning,
    success: mocks.messageSuccess,
  },
}))

vi.mock('../api/article', () => ({
  getArticleForEdit: mocks.getArticleForEdit,
  publishArticle: mocks.publishArticle,
  saveDraft: mocks.saveDraft,
}))

vi.mock('../api/tag', () => ({ getHotTags: mocks.getHotTags }))
vi.mock('../utils/request', () => ({ default: { post: vi.fn() } }))
vi.mock('../components/RichArticleEditor.vue', () => ({
  default: {
    props: ['modelValue'],
    emits: ['update:modelValue', 'legacy-protection', 'word-count', 'upload-image'],
    template: '<textarea aria-label="文章正文" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
}))

const { default: Publish } = await import('./Publish.vue')

const slotStub = { template: '<div><slot /></div>' }
let wrapper

function mountPublish() {
  wrapper = mount(Publish, {
    global: {
      stubs: {
        ElButton: { template: '<button><slot /></button>' },
        ElIcon: slotStub,
        ElOption: slotStub,
        ElSelect: slotStub,
        ElUpload: slotStub,
      },
    },
  })
  return wrapper
}

beforeEach(() => {
  mocks.route.query = { id: '1' }
  mocks.routerPush.mockReset()
  mocks.routerGo.mockReset()
  mocks.getArticleForEdit.mockReset()
  mocks.publishArticle.mockReset().mockResolvedValue({ data: 2 })
  mocks.saveDraft.mockReset().mockResolvedValue({ data: 2 })
  mocks.getHotTags.mockReset().mockResolvedValue({ data: [] })
  mocks.messageError.mockReset()
  mocks.messageWarning.mockReset()
  mocks.messageSuccess.mockReset()
  mocks.leaveGuard = null
  mocks.updateGuard = null
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

describe('Publish edit hydration', () => {
  it('ignores a slow article response after the route switches to another article', async () => {
    const articleA = deferred()
    const articleB = deferred()
    mocks.getArticleForEdit.mockImplementation(id => (String(id) === '1' ? articleA.promise : articleB.promise))

    mountPublish()
    await nextTick()
    expect(wrapper.text()).toContain('正在读取文章')
    expect(wrapper.find('.title-input').exists()).toBe(false)

    mocks.route.query = { id: '2' }
    await nextTick()
    expect(mocks.getArticleForEdit).toHaveBeenCalledWith('2')

    articleA.resolve({ data: { id: 1, title: '文章 A', content: 'A 正文', status: 1 } })
    await flushPromises()
    expect(wrapper.text()).toContain('正在读取文章')
    expect(wrapper.find('.title-input').exists()).toBe(false)

    articleB.resolve({ data: { id: 2, title: '文章 B', content: 'B 正文', status: 1 } })
    await flushPromises()
    expect(wrapper.get('.title-input').element.value).toBe('文章 B')

    await wrapper.get('.title-input').setValue('文章 B 更新')
    const publishButton = wrapper.findAll('button').find(button => button.text().includes('更新发布'))
    await publishButton.trigger('click')
    await flushPromises()

    expect(mocks.publishArticle).toHaveBeenCalledWith(expect.objectContaining({
      id: 2,
      title: '文章 B 更新',
    }))
  })

  it('blocks an article route switch while a draft request is still in flight', async () => {
    const draftGate = deferred()
    mocks.getArticleForEdit.mockResolvedValue({
      data: { id: 1, title: '草稿 A', content: 'A 正文', status: 0 },
    })
    mocks.saveDraft.mockReturnValue(draftGate.promise)
    vi.spyOn(window, 'confirm').mockReturnValue(true)

    mountPublish()
    await flushPromises()
    await wrapper.get('.title-input').setValue('草稿 A 修改')
    const saveButton = wrapper.findAll('button').find(button => button.text().includes('保存草稿'))
    await saveButton.trigger('click')
    await nextTick()

    expect(mocks.updateGuard({ query: { id: '2' } }, { query: { id: '1' } })).toBe(false)
    expect(mocks.messageWarning).toHaveBeenCalled()

    draftGate.resolve({ data: 1 })
    await flushPromises()
  })
})
