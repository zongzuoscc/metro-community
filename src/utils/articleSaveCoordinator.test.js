import { describe, expect, it } from 'vitest'
import { reactive, watch } from 'vue'
import { createArticleSaveCoordinator } from './articleSaveCoordinator'

function wait(milliseconds) {
  return new Promise((resolve) => globalThis.setTimeout(resolve, milliseconds))
}

function deferred() {
  let resolve
  let reject
  const promise = new Promise((nextResolve, nextReject) => {
    resolve = nextResolve
    reject = nextReject
  })
  return { promise, resolve, reject }
}

describe('article save coordinator', () => {
  it('does not schedule a draft while a loaded article is hydrating', async () => {
    const savedPayloads = []
    const form = reactive({ title: '', content: '' })
    const coordinator = createArticleSaveCoordinator({
      autoSaveDelay: 5,
      hasRequiredContent: () => Boolean(form.title && form.content),
      buildPayload: () => ({ ...form }),
      saveDraft: async (payload) => {
        savedPayloads.push(payload)
        return { data: 1 }
      },
      publish: async () => ({ data: true }),
    })

    watch([() => form.title, () => form.content], coordinator.markChanged)
    coordinator.beginHydration()
    form.title = '已发布文章'
    form.content = '从服务端回填的正文'
    await coordinator.completeHydration()

    await wait(20)

    expect(savedPayloads).toEqual([])
    expect(coordinator.state.dirty).toBe(false)
  })

  it('waits for an active draft save before publishing the latest user edits', async () => {
    const firstDraft = deferred()
    const draftPayloads = []
    const publishedPayloads = []
    const form = { title: '标题', content: '第一次编辑' }
    const coordinator = createArticleSaveCoordinator({
      autoSaveDelay: 5,
      hasRequiredContent: () => Boolean(form.title && form.content),
      buildPayload: () => ({ ...form }),
      saveDraft: (payload) => {
        draftPayloads.push(payload)
        return firstDraft.promise
      },
      publish: async (payload) => {
        publishedPayloads.push(payload)
        return { data: true }
      },
    })

    coordinator.markChanged()
    await wait(10)
    expect(draftPayloads).toEqual([{ title: '标题', content: '第一次编辑' }])

    form.content = '在保存期间补充的第二次编辑'
    coordinator.markChanged()
    const publishing = coordinator.requestPublish()
    firstDraft.resolve({ data: 1 })

    await expect(publishing).resolves.toBe(true)
    expect(publishedPayloads).toEqual([{ title: '标题', content: '在保存期间补充的第二次编辑' }])
  })

  it('does not schedule a second draft after disposal while a save is in flight', async () => {
    const firstDraft = deferred()
    const draftPayloads = []
    const form = { title: '标题', content: '第一次编辑' }
    const coordinator = createArticleSaveCoordinator({
      autoSaveDelay: 5,
      hasRequiredContent: () => Boolean(form.title && form.content),
      buildPayload: () => ({ ...form }),
      saveDraft: (payload) => {
        draftPayloads.push(payload)
        return firstDraft.promise
      },
      publish: async () => ({ data: true }),
    })

    coordinator.markChanged()
    await wait(10)
    form.content = '保存期间的后续编辑'
    coordinator.markChanged()
    coordinator.dispose()
    firstDraft.resolve({ data: 1 })

    await wait(20)
    expect(draftPayloads).toHaveLength(1)
  })

  it('does not retry a failed draft without a later edit', async () => {
    const form = { title: '标题', content: '离线时的正文' }
    let saveAttempts = 0
    const coordinator = createArticleSaveCoordinator({
      autoSaveDelay: 5,
      hasRequiredContent: () => Boolean(form.title && form.content),
      buildPayload: () => ({ ...form }),
      saveDraft: async () => {
        saveAttempts += 1
        throw new Error('network unavailable')
      },
      publish: async () => ({ data: true }),
    })

    coordinator.markChanged()
    await wait(15)

    expect(saveAttempts).toBe(1)
    expect(coordinator.state.failed).toBe(true)

    await wait(20)
    expect(saveAttempts).toBe(1)
    coordinator.dispose()
  })

  it('keeps a failed save visible while the author makes another edit', async () => {
    const form = { title: '标题', content: '第一次编辑' }
    const coordinator = createArticleSaveCoordinator({
      autoSaveDelay: 100,
      hasRequiredContent: () => Boolean(form.title && form.content),
      buildPayload: () => ({ ...form }),
      saveDraft: async () => {
        throw new Error('network unavailable')
      },
      publish: async () => ({ data: true }),
    })

    coordinator.markChanged()
    await coordinator.saveCurrentDraft()
    expect(coordinator.state.failed).toBe(true)

    form.content = '第二次编辑'
    coordinator.markChanged()

    expect(coordinator.state.failed).toBe(true)
    coordinator.dispose()
  })

  it('rejects a manual draft while publication is in flight', async () => {
    const publishGate = deferred()
    const savedPayloads = []
    const form = { title: '标题', content: '准备发布的正文' }
    const coordinator = createArticleSaveCoordinator({
      autoSaveDelay: 100,
      hasRequiredContent: () => Boolean(form.title && form.content),
      buildPayload: () => ({ ...form }),
      saveDraft: async (payload) => {
        savedPayloads.push(payload)
        return { data: 1 }
      },
      publish: () => publishGate.promise,
    })

    coordinator.markChanged()
    const publishing = coordinator.requestPublish()
    await Promise.resolve()

    await expect(coordinator.saveCurrentDraft()).resolves.toBe(false)
    expect(savedPayloads).toEqual([])

    publishGate.resolve({ data: true })
    await expect(publishing).resolves.toBe(true)
  })

  it('keeps edits made during a deferred publication dirty and requires another publish', async () => {
    const firstPublish = deferred()
    const publishedPayloads = []
    const savedPayloads = []
    const form = { id: null, title: '标题', content: '提交时的正文' }
    const coordinator = createArticleSaveCoordinator({
      autoSaveDelay: 5,
      hasRequiredContent: () => Boolean(form.title && form.content),
      buildPayload: () => ({ ...form }),
      saveDraft: async (payload) => {
        savedPayloads.push(payload)
        return { data: 1 }
      },
      publish: (payload) => {
        publishedPayloads.push(payload)
        return firstPublish.promise
      },
      onPublished: (response) => {
        form.id = response.data
      },
    })

    coordinator.markChanged()
    const publishing = coordinator.requestPublish()
    await Promise.resolve()

    form.content = '发布期间新增的内容'
    coordinator.markChanged()
    firstPublish.resolve({ data: 91 })

    await expect(publishing).resolves.toBe(false)
    expect(publishedPayloads).toEqual([{ id: null, title: '标题', content: '提交时的正文' }])
    expect(coordinator.state.publishOutdated).toBe(true)
    expect(coordinator.state.dirty).toBe(true)
    await wait(15)
    expect(savedPayloads).toEqual([])

    await expect(coordinator.requestPublish()).resolves.toBe(true)
    expect(publishedPayloads).toEqual([
      { id: null, title: '标题', content: '提交时的正文' },
      { id: 91, title: '标题', content: '发布期间新增的内容' },
    ])
    expect(coordinator.state.publishOutdated).toBe(false)
    expect(coordinator.state.dirty).toBe(false)
    coordinator.dispose()
  })

  it('blocks autosave and publication while persistence is protected', async () => {
    const savedPayloads = []
    const publishedPayloads = []
    const form = { title: '旧文章', content: '<iframe src="https://video.example/embed"></iframe>' }
    let protectedContent = true
    const coordinator = createArticleSaveCoordinator({
      autoSaveDelay: 5,
      hasRequiredContent: () => Boolean(form.title && form.content),
      canPersist: () => !protectedContent,
      buildPayload: () => ({ ...form }),
      saveDraft: async (payload) => {
        savedPayloads.push(payload)
        return { data: 1 }
      },
      publish: async (payload) => {
        publishedPayloads.push(payload)
        return { data: true }
      },
    })

    coordinator.markChanged()
    await wait(15)

    await expect(coordinator.saveCurrentDraft()).resolves.toBe(false)
    await expect(coordinator.requestPublish()).resolves.toBe(false)
    expect(savedPayloads).toEqual([])
    expect(publishedPayloads).toEqual([])

    protectedContent = false
    coordinator.markChanged()
    await wait(10)
    expect(savedPayloads).toEqual([{ title: '旧文章', content: '<iframe src="https://video.example/embed"></iframe>' }])
    coordinator.dispose()
  })

  it('does not downgrade a submitted article to draft when the follow-up publish fails', async () => {
    const firstPublish = deferred()
    const savedPayloads = []
    let publishAttempts = 0
    const form = { id: null, title: '标题', content: '提交时的正文' }
    const coordinator = createArticleSaveCoordinator({
      autoSaveDelay: 5,
      hasRequiredContent: () => Boolean(form.title && form.content),
      buildPayload: () => ({ ...form }),
      saveDraft: async (payload) => {
        savedPayloads.push(payload)
        return { data: payload.id }
      },
      publish: () => {
        publishAttempts += 1
        return publishAttempts === 1
          ? firstPublish.promise
          : Promise.reject(new Error('network unavailable'))
      },
      onPublished: (response) => {
        form.id = response.data
      },
    })

    coordinator.markChanged()
    const initialPublishing = coordinator.requestPublish()
    form.content = '发布期间新增的正文'
    coordinator.markChanged()
    firstPublish.resolve({ data: 92 })

    await expect(initialPublishing).resolves.toBe(false)
    await expect(coordinator.requestPublish()).resolves.toBe(false)
    await wait(15)

    expect(form.id).toBe(92)
    expect(coordinator.state.publishOutdated).toBe(true)
    expect(coordinator.state.dirty).toBe(true)
    expect(savedPayloads).toEqual([])
    coordinator.dispose()
  })

  it('does not silently withdraw an outdated publication through the draft endpoint', async () => {
    const firstPublish = deferred()
    const savedPayloads = []
    const form = { id: null, title: '标题', content: '提交时的正文' }
    const coordinator = createArticleSaveCoordinator({
      autoSaveDelay: 5,
      hasRequiredContent: () => Boolean(form.title && form.content),
      buildPayload: () => ({ ...form }),
      saveDraft: async (payload) => {
        savedPayloads.push(payload)
        return { data: payload.id ?? 93 }
      },
      publish: () => firstPublish.promise,
      onPublished: (response) => {
        form.id = response.data
      },
    })

    coordinator.markChanged()
    const publishing = coordinator.requestPublish()
    form.content = '发布期间新增的正文'
    coordinator.markChanged()
    firstPublish.resolve({ data: 93 })
    await expect(publishing).resolves.toBe(false)

    await expect(coordinator.saveCurrentDraft()).resolves.toBe(false)
    expect(coordinator.state.publishOutdated).toBe(true)

    form.content = '等待再次发布的第三版'
    coordinator.markChanged()
    await wait(15)

    expect(savedPayloads).toEqual([])
    coordinator.dispose()
  })

  it('can block draft persistence for an already-published article without blocking republish', async () => {
    const savedPayloads = []
    const publishedPayloads = []
    const form = { id: 94, title: '已发布文章', content: '编辑中的新正文' }
    const coordinator = createArticleSaveCoordinator({
      autoSaveDelay: 5,
      hasRequiredContent: () => true,
      canSaveDraft: () => false,
      canPublish: () => true,
      buildPayload: () => ({ ...form }),
      saveDraft: async (payload) => {
        savedPayloads.push(payload)
        return { data: 94 }
      },
      publish: async (payload) => {
        publishedPayloads.push(payload)
        return { data: 94 }
      },
    })

    coordinator.markChanged()
    await wait(15)

    await expect(coordinator.saveCurrentDraft()).resolves.toBe(false)
    await expect(coordinator.requestPublish()).resolves.toBe(true)
    expect(savedPayloads).toEqual([])
    expect(publishedPayloads).toEqual([{ id: 94, title: '已发布文章', content: '编辑中的新正文' }])
    coordinator.dispose()
  })
})
