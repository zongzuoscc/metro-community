import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createQualifiedArticleView } from './qualifiedArticleView'

describe('qualified article view', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('reports exactly once after eight cumulative visible seconds', () => {
    const report = vi.fn()
    const tracker = createQualifiedArticleView({
      thresholdMs: 8000,
      report,
      isVisible: () => true
    })

    tracker.start(7, 42)
    vi.advanceTimersByTime(5000)
    tracker.setVisible(false)
    vi.advanceTimersByTime(5000)
    tracker.setVisible(true)
    vi.advanceTimersByTime(2999)
    expect(report).not.toHaveBeenCalled()

    vi.advanceTimersByTime(1)
    vi.advanceTimersByTime(8000)

    expect(report).toHaveBeenCalledTimes(1)
    expect(report).toHaveBeenCalledWith(7, 42)
  })

  it('resets elapsed time and metadata when the article changes', () => {
    const report = vi.fn()
    const tracker = createQualifiedArticleView({ thresholdMs: 8000, report, isVisible: () => true })

    tracker.start(7, 42)
    vi.advanceTimersByTime(7000)
    tracker.reset(8, undefined)
    vi.advanceTimersByTime(1000)
    expect(report).not.toHaveBeenCalled()

    vi.advanceTimersByTime(7000)
    expect(report).toHaveBeenCalledTimes(1)
    expect(report).toHaveBeenCalledWith(8, undefined)
  })

  it('disposes timers and visibility listeners without reporting', () => {
    const report = vi.fn()
    const addVisibilityListener = vi.fn()
    const removeVisibilityListener = vi.fn()
    const tracker = createQualifiedArticleView({
      thresholdMs: 8000,
      report,
      isVisible: () => true,
      addVisibilityListener,
      removeVisibilityListener
    })

    tracker.start(7, 42)
    expect(addVisibilityListener).toHaveBeenCalledTimes(1)
    tracker.dispose()
    vi.advanceTimersByTime(8000)

    expect(removeVisibilityListener).toHaveBeenCalledTimes(1)
    expect(report).not.toHaveBeenCalled()
  })

  it('uses visibilitychange events to pause and resume accumulation', () => {
    let visible = true
    let visibilityListener
    const report = vi.fn()
    const tracker = createQualifiedArticleView({
      thresholdMs: 8000,
      report,
      isVisible: () => visible,
      addVisibilityListener: (listener) => { visibilityListener = listener },
      removeVisibilityListener: vi.fn()
    })

    tracker.start(7, 42)
    vi.advanceTimersByTime(4000)
    visible = false
    visibilityListener()
    vi.advanceTimersByTime(8000)
    expect(report).not.toHaveBeenCalled()

    visible = true
    visibilityListener()
    vi.advanceTimersByTime(4000)
    expect(report).toHaveBeenCalledTimes(1)
  })
})
