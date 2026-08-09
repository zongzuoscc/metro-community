import { nextTick, reactive } from 'vue'

export function createArticleSaveCoordinator({
  autoSaveDelay,
  hasRequiredContent,
  canPersist = () => true,
  canSaveDraft = canPersist,
  canPublish = canPersist,
  buildPayload,
  saveDraft,
  publish,
  onDraftSaved = () => {},
  onDraftFailed = () => {},
  onPublished = () => {},
}) {
  const state = reactive({
    dirty: false,
    failed: false,
    hydrating: false,
    saving: false,
    publishing: false,
    publishOutdated: false,
    disposed: false,
  })

  let autoSaveTimer
  let changeVersion = 0
  let savedVersion = 0
  let inFlightSave
  let inFlightPublish
  let publishRequested = false
  let contextVersion = 0

  function clearAutoSaveTimer() {
    if (autoSaveTimer) {
      globalThis.clearTimeout(autoSaveTimer)
      autoSaveTimer = undefined
    }
  }

  function scheduleAutoSave() {
    clearAutoSaveTimer()

    if (state.disposed || state.hydrating || state.saving || state.publishOutdated || publishRequested || !canSaveDraft() || !hasRequiredContent()) return

    autoSaveTimer = globalThis.setTimeout(() => {
      autoSaveTimer = undefined
      if (!state.disposed) saveCurrentDraft()
    }, autoSaveDelay)
  }

  function beginHydration() {
    clearAutoSaveTimer()
    contextVersion += 1
    state.hydrating = true
  }

  function finishHydration() {
    changeVersion = 0
    savedVersion = 0
    state.dirty = false
    state.failed = false
    state.publishOutdated = false
    state.hydrating = false
  }

  async function completeHydration() {
    await nextTick()
    if (!state.disposed) finishHydration()
  }

  function markChanged() {
    if (state.disposed || state.hydrating) return

    changeVersion += 1
    state.dirty = true
    scheduleAutoSave()
  }

  async function saveCurrentDraft() {
    if (state.disposed || state.saving || state.publishOutdated || publishRequested || inFlightPublish || !canSaveDraft() || !hasRequiredContent()) return false

    clearAutoSaveTimer()
    const requestVersion = changeVersion
    const requestContextVersion = contextVersion
    state.saving = true
    const currentSave = (async () => {
      try {
        const response = await saveDraft(buildPayload())
        if (state.disposed || requestContextVersion !== contextVersion) return false
        savedVersion = requestVersion
        state.dirty = changeVersion !== savedVersion
        state.failed = false
        onDraftSaved(response)
        return true
      } catch (error) {
        if (state.disposed || requestContextVersion !== contextVersion) return false
        state.failed = true
        state.dirty = true
        onDraftFailed(error)
        return false
      } finally {
        state.saving = false
        const changedDuringSave = changeVersion !== requestVersion
        const needsCurrentContextSave = requestContextVersion !== contextVersion || changedDuringSave
        if (!state.disposed && !publishRequested && !inFlightPublish && needsCurrentContextSave && state.dirty) {
          scheduleAutoSave()
        }
      }
    })()

    inFlightSave = currentSave
    try {
      return await currentSave
    } finally {
      if (inFlightSave === currentSave) inFlightSave = undefined
    }
  }

  async function publishCurrentArticle(requestContextVersion) {
    const requestVersion = changeVersion
    try {
      const response = await publish(buildPayload())
      if (state.disposed || requestContextVersion !== contextVersion) return false
      onPublished(response)
      savedVersion = requestVersion
      state.dirty = changeVersion !== savedVersion
      state.failed = false
      state.publishOutdated = state.dirty
      return !state.publishOutdated
    } catch (error) {
      if (state.disposed || requestContextVersion !== contextVersion) return false
      state.failed = true
      state.dirty = true
      return false
    }
  }

  function requestPublish() {
    if (state.disposed || inFlightPublish || !canPublish() || !hasRequiredContent()) {
      return inFlightPublish || Promise.resolve(false)
    }

    clearAutoSaveTimer()
    const requestContextVersion = contextVersion
    publishRequested = true
    state.publishing = true
    const currentPublish = (async () => {
      if (inFlightSave) await inFlightSave
      if (state.disposed || requestContextVersion !== contextVersion) return false
      return publishCurrentArticle(requestContextVersion)
    })()

    inFlightPublish = currentPublish
    return currentPublish.finally(() => {
      if (inFlightPublish === currentPublish) inFlightPublish = undefined
      publishRequested = false
      state.publishing = false
      if (!state.disposed && state.dirty && !state.publishing) scheduleAutoSave()
    })
  }

  function dispose() {
    state.disposed = true
    contextVersion += 1
    clearAutoSaveTimer()
  }

  return {
    state,
    beginHydration,
    finishHydration,
    completeHydration,
    markChanged,
    saveCurrentDraft,
    requestPublish,
    dispose,
  }
}
