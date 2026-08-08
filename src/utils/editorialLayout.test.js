import { describe, expect, it } from 'vitest'
import { desktopContentWidth } from './editorialLayout'

describe('editorial layout', () => {
  it('caps wide reading layouts while keeping a usable narrow fallback', () => {
    expect(desktopContentWidth(1600)).toBe(1180)
    expect(desktopContentWidth(375)).toBe(343)
  })
})
