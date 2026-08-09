export function canAutosaveArticleDraft(isEdit, status) {
  if (!isEdit) return true
  return status != null && Number(status) === 0
}

export function isArticleEditReady(isEdit, loadState) {
  return !isEdit || loadState === 'ready'
}

export function shouldConfirmArticleLeave({ dirty, publishOutdated }) {
  return Boolean(dirty || publishOutdated)
}
