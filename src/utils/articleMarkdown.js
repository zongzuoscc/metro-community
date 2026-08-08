import { unified } from 'unified'
import remarkParse from 'remark-parse'

const unsafeImageScheme = /^(?:data|blob):/i
const markdownParser = unified().use(remarkParse)

export function normalizeMarkdown(value = '') {
  return value.replace(/[ \t]+$/gm, '').replace(/\n{3,}/g, '\n\n').trim()
}

function visitMarkdownNodes(node, visitor) {
  visitor(node)

  for (const child of node.children ?? []) {
    visitMarkdownNodes(child, visitor)
  }
}

function sourceRange(node) {
  const start = node.position?.start.offset
  const end = node.position?.end.offset

  return Number.isInteger(start) && Number.isInteger(end) ? { start, end } : null
}

function removeRanges(value, replacements) {
  return replacements
    .sort((left, right) => right.start - left.start)
    .reduce(
      (result, replacement) =>
        `${result.slice(0, replacement.start)}${replacement.value}${result.slice(replacement.end)}`,
      value,
    )
}

function definitionRemovalRange(value, definition) {
  const range = sourceRange(definition)
  if (!range) return null

  let { start, end } = range

  if (!value.slice(end).trim()) {
    while (start > 0 && /\s/.test(value[start - 1])) start -= 1
  } else {
    while (end < value.length && /\s/.test(value[end])) end += 1
  }

  return { start, end }
}

/**
 * Removes data/blob image destinations while preserving the exact source of
 * non-image Markdown. `remark-parse` supplies CommonMark AST source offsets,
 * so code spans, code blocks, nested lists, and blockquotes are distinguished
 * by parser semantics instead of by line-based heuristics.
 */
export function sanitizeMarkdownImageDestinations(value = '') {
  const tree = markdownParser.parse(value)
  const definitions = new Map()
  const referenceUses = new Map()
  const unsafeImageReferences = new Set()
  const replacements = []

  visitMarkdownNodes(tree, node => {
    if (node.type === 'definition') definitions.set(node.identifier, node)

    if (node.type === 'image') {
      const range = sourceRange(node)
      if (range && unsafeImageScheme.test(node.url)) {
        replacements.push({ ...range, value: node.alt })
      }
    }

    if (node.type === 'imageReference' || node.type === 'linkReference') {
      const uses = referenceUses.get(node.identifier) ?? { image: 0, link: 0 }
      uses[node.type === 'imageReference' ? 'image' : 'link'] += 1
      referenceUses.set(node.identifier, uses)
    }
  })

  visitMarkdownNodes(tree, node => {
    if (node.type !== 'imageReference') return

    const definition = definitions.get(node.identifier)
    const range = sourceRange(node)

    if (!definition || !range || !unsafeImageScheme.test(definition.url)) return

    unsafeImageReferences.add(node.identifier)
    replacements.push({ ...range, value: node.alt })
  })

  for (const identifier of unsafeImageReferences) {
    const uses = referenceUses.get(identifier)
    const definition = definitions.get(identifier)

    if (!definition || uses?.link) continue

    const range = definitionRemovalRange(value, definition)
    if (range) replacements.push({ ...range, value: '' })
  }

  return removeRanges(value, replacements)
}

export function estimateReadingMinutes(value = '') {
  const characters = value.replace(/[`*_#>[\]()!-]/g, '').replace(/\s/g, '').length
  return Math.max(1, Math.ceil(characters / 400))
}
