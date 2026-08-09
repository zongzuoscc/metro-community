import { describe, expect, it, vi } from 'vitest'
import { createLatestRequestGuard } from './latestRequestGuard'

describe('latest request guard', () => {
  it('invalidates an older in-flight request without invalidating the new one', () => {
    const guard = createLatestRequestGuard()
    const first = guard.capture()

    guard.invalidate()
    const second = guard.capture()

    expect(first.isCurrent()).toBe(false)
    expect(second.isCurrent()).toBe(true)
  })

  it('commits side effects only for the current generation', () => {
    const guard = createLatestRequestGuard()
    const first = guard.capture()
    const firstCommit = vi.fn()
    const secondCommit = vi.fn()

    guard.invalidate()
    const second = guard.capture()
    first.commit(firstCommit)
    second.commit(secondCommit)

    expect(firstCommit).not.toHaveBeenCalled()
    expect(secondCommit).toHaveBeenCalledTimes(1)
  })
})
