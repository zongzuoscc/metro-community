import { describe, expect, it } from 'vitest'
import { nextDraftState } from './draftState'

describe('nextDraftState', () => {
  it('uses stable labels for each save state', () => {
    expect(nextDraftState(true, false, false)).toEqual({ label: '未保存', tone: 'muted' })
    expect(nextDraftState(true, true, false)).toEqual({ label: '正在保存', tone: 'saving' })
    expect(nextDraftState(false, false, false)).toEqual({ label: '已保存', tone: 'saved' })
    expect(nextDraftState(true, false, true)).toEqual({ label: '保存失败', tone: 'error' })
  })

  it('keeps a failure visible until new input is saved', () => {
    expect(nextDraftState(true, false, true)).toEqual({ label: '保存失败', tone: 'error' })
    expect(nextDraftState(false, false, false)).toEqual({ label: '已保存', tone: 'saved' })
  })
})
