import { describe, expect, it } from 'vitest'
import { reactive, watch } from 'vue'
import { createArticleSaveCoordinator } from './articleSaveCoordinator'

function wait(milliseconds) {
  return new Promise((resolve) => globalThis.setTimeout(resolve, milliseconds))
}

function deferred() {
  let resolve
  const promise = new Promise((nextResolve) => {
    resolve = nextResolve
  })
  return { promise, resolve }
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
})
