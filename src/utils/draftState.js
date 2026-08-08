export function nextDraftState(dirty, saving, failed) {
  if (failed) return { label: '保存失败', tone: 'error' }
  if (saving) return { label: '正在保存', tone: 'saving' }
  return dirty ? { label: '未保存', tone: 'muted' } : { label: '已保存', tone: 'saved' }
}
