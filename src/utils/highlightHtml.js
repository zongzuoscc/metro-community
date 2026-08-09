const HTML_ENTITIES = Object.freeze({
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;'
})

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, character => HTML_ENTITIES[character])
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

export function highlightSafeHtml(value, keyword = '', enabled = false) {
  const text = String(value ?? '')
  const literalKeyword = String(keyword ?? '')

  if (!enabled || !literalKeyword) return escapeHtml(text)

  const matcher = new RegExp(escapeRegExp(literalKeyword), 'gi')
  let result = ''
  let lastIndex = 0

  for (const match of text.matchAll(matcher)) {
    result += escapeHtml(text.slice(lastIndex, match.index))
    result += `<span class="search-highlight">${escapeHtml(match[0])}</span>`
    lastIndex = match.index + match[0].length
  }

  return result + escapeHtml(text.slice(lastIndex))
}
