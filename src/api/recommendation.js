import request from '../utils/request'

export const getRecommendationFeed = (cursor, size = 10) => {
  return request.get('/api/recommendations/feed', {
    params: { cursor: cursor || undefined, size }
  })
}

export const reportQualifiedView = (articleId, exposureId) => {
  const payload = exposureId == null ? {} : { exposureId }
  return request.post(`/api/recommendations/views/${articleId}`, payload, { silent: true })
}

export const toFeedCards = (feed = {}) => {
  const items = Array.isArray(feed.items) ? feed.items : []
  return items.map((item) => {
    const card = { ...(item?.article || {}) }
    if (item?.reason) card.recommendationReason = item.reason
    if (item?.source) card.recommendationSource = item.source
    if (item?.exposureId != null) card.recommendationExposureId = item.exposureId
    return card
  })
}
