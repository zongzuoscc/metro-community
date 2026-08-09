import { beforeEach, describe, expect, it, vi } from 'vitest'

const { get, post } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn()
}))

vi.mock('../utils/request', () => ({
  default: { get, post }
}))

import {
  getRecommendationFeed,
  reportQualifiedView,
  toFeedCards
} from './recommendation'

describe('recommendation frontend contract', () => {
  beforeEach(() => {
    get.mockReset()
    post.mockReset()
  })

  it('maps personalized response items to ordinary article cards without losing delivery metadata', () => {
    expect(toFeedCards({
      mode: 'PERSONALIZED',
      items: [{
        article: { id: 1, title: 'Redis 实战' },
        reason: '因为你常看 Redis',
        source: 'TAG',
        exposureId: 42
      }]
    })).toEqual([{
      id: 1,
      title: 'Redis 实战',
      recommendationReason: '因为你常看 Redis',
      recommendationSource: 'TAG',
      recommendationExposureId: 42
    }])
  })

  it('calls the authenticated feed and qualified-view endpoints with their exact payloads', async () => {
    get.mockResolvedValue({ data: { items: [] } })
    post.mockResolvedValue({ data: null })

    await getRecommendationFeed('next page', 12)
    await reportQualifiedView(7, 42)
    await reportQualifiedView(8)

    expect(get).toHaveBeenCalledWith('/api/recommendations/feed', {
      params: { cursor: 'next page', size: 12 }
    })
    expect(post).toHaveBeenNthCalledWith(1, '/api/recommendations/views/7', { exposureId: 42 }, { silent: true })
    expect(post).toHaveBeenNthCalledWith(2, '/api/recommendations/views/8', {}, { silent: true })
  })
})
