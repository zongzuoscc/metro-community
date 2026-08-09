const defaultIsVisible = () => {
  return typeof document === 'undefined' || document.visibilityState === 'visible'
}

const defaultAddVisibilityListener = (listener) => {
  if (typeof document !== 'undefined') document.addEventListener('visibilitychange', listener)
}

const defaultRemoveVisibilityListener = (listener) => {
  if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', listener)
}

export const createQualifiedArticleView = ({
  thresholdMs = 8000,
  report,
  onError = () => {},
  now = () => Date.now(),
  setTimeout: scheduleTimeout = globalThis.setTimeout,
  clearTimeout: cancelTimeout = globalThis.clearTimeout,
  isVisible = defaultIsVisible,
  addVisibilityListener = defaultAddVisibilityListener,
  removeVisibilityListener = defaultRemoveVisibilityListener
}) => {
  if (!Number.isFinite(thresholdMs) || thresholdMs <= 0) {
    throw new TypeError('thresholdMs must be a positive finite number')
  }
  if (typeof report !== 'function') throw new TypeError('report must be a function')

  let articleId
  let exposureId
  let accumulatedMs = 0
  let visibleSince = null
  let timerId = null
  let visible = false
  let started = false
  let disposed = false
  let reported = false
  let listenerAttached = false

  const clearScheduled = () => {
    if (timerId != null) {
      cancelTimeout(timerId)
      timerId = null
    }
  }

  const accumulateVisibleTime = () => {
    if (visibleSince == null) return
    const timestamp = now()
    accumulatedMs += Math.max(0, timestamp - visibleSince)
    visibleSince = timestamp
  }

  const reportOnce = () => {
    if (disposed || reported || articleId == null) return
    clearScheduled()
    reported = true
    visibleSince = null
    try {
      Promise.resolve(report(articleId, exposureId)).catch(onError)
    } catch (error) {
      onError(error)
    }
  }

  const schedule = () => {
    if (disposed || !started || !visible || reported || articleId == null) return
    clearScheduled()
    const remainingMs = thresholdMs - accumulatedMs
    if (remainingMs <= 0) {
      reportOnce()
      return
    }
    if (visibleSince == null) visibleSince = now()
    timerId = scheduleTimeout(() => {
      timerId = null
      accumulateVisibleTime()
      if (accumulatedMs >= thresholdMs) reportOnce()
      else schedule()
    }, remainingMs)
  }

  const setVisible = (nextVisible) => {
    if (disposed || !started || reported) return
    const next = Boolean(nextVisible)
    if (next === visible) return
    if (!next) {
      accumulateVisibleTime()
      visibleSince = null
      visible = false
      clearScheduled()
      if (accumulatedMs >= thresholdMs) reportOnce()
      return
    }
    visible = true
    visibleSince = now()
    schedule()
  }

  const handleVisibilityChange = () => setVisible(isVisible())

  const reset = (nextArticleId, nextExposureId) => {
    if (disposed) return
    clearScheduled()
    articleId = nextArticleId
    exposureId = nextExposureId
    accumulatedMs = 0
    visibleSince = null
    visible = false
    reported = false
    if (started) setVisible(isVisible())
  }

  const start = (nextArticleId, nextExposureId) => {
    if (disposed) return
    if (!listenerAttached) {
      addVisibilityListener(handleVisibilityChange)
      listenerAttached = true
    }
    started = true
    reset(nextArticleId, nextExposureId)
  }

  const dispose = () => {
    if (disposed) return
    clearScheduled()
    if (listenerAttached) removeVisibilityListener(handleVisibilityChange)
    listenerAttached = false
    visibleSince = null
    visible = false
    started = false
    disposed = true
  }

  return { start, reset, setVisible, dispose }
}
