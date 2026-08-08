import { describe, expect, it } from 'vitest'
import {
  estimateReadingMinutes,
  normalizeMarkdown,
  sanitizeMarkdownImageDestinations,
} from './articleMarkdown'

describe('article Markdown helpers', () => {
  it('counts Chinese and Latin text without counting Markdown markers', () => {
    expect(estimateReadingMinutes('## 标题\n\nhello **Metro**')).toBe(1)
  })

  it('normalizes a document before it is sent to the existing API', () => {
    expect(normalizeMarkdown('标题  \n\n\n正文')).toBe('标题\n\n正文')
  })

  it('removes a data image destination from initial editor Markdown', () => {
    const initialMarkdown = '开头\n\n![内嵌图](data:image/png;base64,AAAA)\n\n结尾'

    expect(sanitizeMarkdownImageDestinations(initialMarkdown)).toBe('开头\n\n内嵌图\n\n结尾')
  })

  it('removes a blob image destination from replacement editor Markdown', () => {
    const replacementMarkdown = '正文\n\n![临时图](blob:https://metro.test/image-id)'

    expect(sanitizeMarkdownImageDestinations(replacementMarkdown)).toBe('正文\n\n临时图')
  })
})
