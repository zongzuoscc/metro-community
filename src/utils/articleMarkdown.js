export function normalizeMarkdown(value = '') {
  return value.replace(/[ \t]+$/gm, '').replace(/\n{3,}/g, '\n\n').trim()
}

export function sanitizeMarkdownImageDestinations(value = '') {
  return value.replace(/!\[([^\]]*)\]\(\s*(?:data|blob):[^)]*\)/gi, '$1')
}

export function estimateReadingMinutes(value = '') {
  const characters = value.replace(/[`*_#>[\]()!-]/g, '').replace(/\s/g, '').length
  return Math.max(1, Math.ceil(characters / 400))
}
