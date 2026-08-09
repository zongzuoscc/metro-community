import { describe, expect, it } from 'vitest'
import { highlightSafeHtml } from './highlightHtml'

describe('highlightSafeHtml', () => {
  it('escapes untrusted community content before rendering it as HTML', () => {
    expect(highlightSafeHtml('<img src=x onerror=alert(1)>'))
      .toBe('&lt;img src=x onerror=alert(1)&gt;')
  })

  it('highlights literal search text even when it contains regular-expression syntax', () => {
    expect(highlightSafeHtml('C++ [guide] & notes', '[guide]', true))
      .toBe('C++ <span class="search-highlight">[guide]</span> &amp; notes')
  })
})
