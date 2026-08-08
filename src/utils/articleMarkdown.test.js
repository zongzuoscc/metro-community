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

  it('removes angle-bracket and reference-style unsafe image destinations', () => {
    const markdown =
      '![尖括号图](<blob:https://metro.example/image-id>)\n\n![引用图][route-map]\n\n[route-map]: data:image/svg+xml,%3Csvg%3E%3C%2Fsvg%3E "路线图"'

    expect(sanitizeMarkdownImageDestinations(markdown)).toBe('尖括号图\n\n引用图')
  })

  it('leaves safe images and non-image links as standard Markdown', () => {
    const markdown = [
      '[乘车指南](https://metro.example/guide)',
      '![安全线路图](https://metro.example/assets/station-map.png)',
    ].join('\n\n')

    expect(sanitizeMarkdownImageDestinations(markdown)).toBe(markdown)
  })

  it('preserves escaped image literals and Markdown code examples verbatim', () => {
    const markdown = [
      '\\![转义示例](data:image/png;base64,AAAA)',
      '`![行内代码](blob:https://metro.example/image-id)`',
      '```markdown',
      '![围栏代码](data:image/png;base64,AAAA)',
      '```',
    ].join('\n\n')

    expect(sanitizeMarkdownImageDestinations(markdown)).toBe(markdown)
  })

  it('sanitizes Markdown images that continue a paragraph or list item', () => {
    const markdown = [
      '    ![普通缩进代码](data:image/png;base64,BBBB)',
      '',
      '正文',
      '    ![段落续行](data:image/png;base64,AAAA)',
      '',
      '- 条目',
      '    ![列表续行](blob:https://metro.example/image-id)',
      '>     ![引用代码](data:image/png;base64,CCCC)',
    ].join('\n')

    expect(sanitizeMarkdownImageDestinations(markdown)).toBe([
      '    ![普通缩进代码](data:image/png;base64,BBBB)',
      '',
      '正文',
      '    段落续行',
      '',
      '- 条目',
      '    列表续行',
      '>     ![引用代码](data:image/png;base64,CCCC)',
    ].join('\n'))
  })

  it('sanitizes full, collapsed, and shortcut references without changing data links', () => {
    const markdown = [
      '![完整引用][full-image]',
      '![折叠引用][]',
      '![快捷引用]',
      '',
      '[full-image]: data:image/png;base64,FULL',
      '[折叠引用]: <blob:https://metro.example/collapsed>',
      '[快捷引用]: data:image/png;base64,SHORTCUT',
      '',
      '[纯文本资料](data:text/plain,metro)',
    ].join('\n')

    expect(sanitizeMarkdownImageDestinations(markdown)).toBe([
      '完整引用',
      '折叠引用',
      '快捷引用',
      '',
      '[纯文本资料](data:text/plain,metro)',
    ].join('\n'))
  })

  it('uses the first duplicate definition when an image reference resolves to an unsafe URL', () => {
    const markdown = [
      '![首条危险定义][route-map]',
      '',
      '[route-map]: data:image/png;base64,FIRST',
      '[route-map]: https://metro.example/assets/second-definition.png',
    ].join('\n')

    expect(sanitizeMarkdownImageDestinations(markdown)).toBe([
      '首条危险定义',
      '',
      '[route-map]: https://metro.example/assets/second-definition.png',
    ].join('\n'))
  })

  it('keeps a safe image when a later duplicate definition is unsafe', () => {
    const markdown = [
      '![首条安全定义][route-map]',
      '',
      '[route-map]: https://metro.example/assets/first-definition.png',
      '[route-map]: data:image/png;base64,SECOND',
    ].join('\n')

    expect(sanitizeMarkdownImageDestinations(markdown)).toBe(markdown)
  })
})
