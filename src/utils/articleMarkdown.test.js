import { describe, expect, it } from 'vitest'
import { estimateReadingMinutes, normalizeMarkdown } from './articleMarkdown'

describe('article Markdown helpers', () => {
  it('counts Chinese and Latin text without counting Markdown markers', () => {
    expect(estimateReadingMinutes('## 标题\n\nhello **Metro**')).toBe(1)
  })

  it('normalizes a document before it is sent to the existing API', () => {
    expect(normalizeMarkdown('标题  \n\n\n正文')).toBe('标题\n\n正文')
  })
})
