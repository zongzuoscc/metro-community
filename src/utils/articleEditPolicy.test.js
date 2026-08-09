import { describe, expect, it } from 'vitest'
import { canAutosaveArticleDraft, isArticleEditReady, shouldConfirmArticleLeave } from './articleEditPolicy'

describe('article edit persistence policy', () => {
  it('autosaves new articles and drafts but never downgrades published or reviewing articles', () => {
    expect(canAutosaveArticleDraft(false, null)).toBe(true)
    expect(canAutosaveArticleDraft(true, 0)).toBe(true)
    expect(canAutosaveArticleDraft(true, 1)).toBe(false)
    expect(canAutosaveArticleDraft(true, 2)).toBe(false)
    expect(canAutosaveArticleDraft(true, null)).toBe(false)
  })

  it('requires confirmation whenever edits or a newer unpublished version remain', () => {
    expect(shouldConfirmArticleLeave({ dirty: false, publishOutdated: false })).toBe(false)
    expect(shouldConfirmArticleLeave({ dirty: true, publishOutdated: false })).toBe(true)
    expect(shouldConfirmArticleLeave({ dirty: false, publishOutdated: true })).toBe(true)
  })

  it('keeps edit controls unavailable until the requested article is hydrated successfully', () => {
    expect(isArticleEditReady(false, 'ready')).toBe(true)
    expect(isArticleEditReady(true, 'loading')).toBe(false)
    expect(isArticleEditReady(true, 'error')).toBe(false)
    expect(isArticleEditReady(true, 'ready')).toBe(true)
  })
})
