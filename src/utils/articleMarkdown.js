export function normalizeMarkdown(value = '') {
  return value.replace(/[ \t]+$/gm, '').replace(/\n{3,}/g, '\n\n').trim()
}

const unsafeImageScheme = /^(?:data|blob):/i

function isEscapedAt(value, index) {
  let backslashCount = 0

  for (let cursor = index - 1; cursor >= 0 && value[cursor] === '\\'; cursor -= 1) {
    backslashCount += 1
  }

  return backslashCount % 2 === 1
}

function nextLineStart(value, lineStart) {
  const lineEnd = value.indexOf('\n', lineStart)
  return lineEnd === -1 ? value.length : lineEnd + 1
}

function lineAt(value, lineStart) {
  const lineEnd = value.indexOf('\n', lineStart)
  return value.slice(lineStart, lineEnd === -1 ? value.length : lineEnd).replace(/\r$/, '')
}

function findFencedCodeRanges(value) {
  const ranges = []
  let lineStart = 0

  while (lineStart < value.length) {
    const openingFence = /^ {0,3}(`{3,}|~{3,})/.exec(lineAt(value, lineStart))

    if (!openingFence) {
      lineStart = nextLineStart(value, lineStart)
      continue
    }

    const marker = openingFence[1]
    const closingFence = new RegExp(`^ {0,3}${marker[0]}{${marker.length},}[ \\t]*$`)
    let closingLineStart = nextLineStart(value, lineStart)

    while (closingLineStart < value.length && !closingFence.test(lineAt(value, closingLineStart))) {
      closingLineStart = nextLineStart(value, closingLineStart)
    }

    const end = closingLineStart < value.length
      ? nextLineStart(value, closingLineStart)
      : value.length
    ranges.push({ start: lineStart, end })
    lineStart = end
  }

  return ranges
}

function isIndentedCodeLine(line) {
  return /^(?: {4}|\t)/.test(line)
}

function findIndentedCodeRanges(value) {
  const ranges = []
  let lineStart = 0

  while (lineStart < value.length) {
    if (!isIndentedCodeLine(lineAt(value, lineStart))) {
      lineStart = nextLineStart(value, lineStart)
      continue
    }

    const start = lineStart
    let end = nextLineStart(value, lineStart)

    while (end < value.length) {
      const line = lineAt(value, end)

      if (!isIndentedCodeLine(line) && !/^[ \t]*$/.test(line)) break
      end = nextLineStart(value, end)
    }

    ranges.push({ start, end })
    lineStart = end
  }

  return ranges
}

function findRangeContaining(ranges, index) {
  return ranges.find(range => range.start <= index && index < range.end)
}

function findProtectedMarkdownRanges(value) {
  const ranges = [...findFencedCodeRanges(value), ...findIndentedCodeRanges(value)]
  let cursor = 0

  while (cursor < value.length) {
    const fencedRange = findRangeContaining(ranges, cursor)
    if (fencedRange) {
      cursor = fencedRange.end
      continue
    }

    if (value[cursor] !== '`' || isEscapedAt(value, cursor)) {
      cursor += 1
      continue
    }

    let markerEnd = cursor + 1
    while (value[markerEnd] === '`') markerEnd += 1
    const marker = value.slice(cursor, markerEnd)
    let closingStart = markerEnd
    let foundClosing = false

    while (closingStart < value.length) {
      const codeFence = findRangeContaining(ranges, closingStart)
      if (codeFence) {
        closingStart = codeFence.end
        continue
      }

      const candidate = value.indexOf(marker, closingStart)
      if (candidate === -1) break
      const candidateRange = findRangeContaining(ranges, candidate)
      if (candidateRange) {
        closingStart = candidateRange.end
        continue
      }
      if (isEscapedAt(value, candidate)) {
        closingStart = candidate + marker.length
        continue
      }

      ranges.push({ start: cursor, end: candidate + marker.length })
      cursor = candidate + marker.length
      foundClosing = true
      break
    }

    if (!foundClosing) cursor = markerEnd
  }

  return ranges.sort((left, right) => left.start - right.start)
}
const referenceDefinition = /^ {0,3}\[((?:\\.|[^\\\]\n])+)\]:[ \t]*(?:<((?:\\.|[^\\>\n])*)>|((?:\\.|[^\s\\])+))(?:[ \t]+(?:"(?:\\.|[^\\"\n])*"|'(?:\\.|[^\\'\n])*'|\((?:\\.|[^\\)\n])*\)))?[ \t]*$/gm

function findClosingDelimiter(value, start, opening, closing) {
  let depth = 1

  for (let index = start + 1; index < value.length; index += 1) {
    const character = value[index]

    if (character === '\\') {
      index += 1
    } else if (character === opening) {
      depth += 1
    } else if (character === closing) {
      depth -= 1
      if (depth === 0) return index
    }
  }

  return -1
}

function findInlineImageEnd(value, openingParenthesis, angleDestinationEnd = -1) {
  let depth = 1
  let quote = ''
  let destinationStarted = angleDestinationEnd !== -1
  let titleMayStart = false

  for (let index = openingParenthesis + 1; index < value.length; index += 1) {
    const character = value[index]

    if (character === '\\') {
      index += 1
      continue
    }

    if (index < angleDestinationEnd) continue

    if (quote) {
      if (character === quote) quote = ''
      continue
    }

    if (/[ \t\n]/.test(character)) {
      if (destinationStarted) titleMayStart = true
      continue
    }

    if (titleMayStart && (character === '"' || character === "'")) {
      quote = character
    } else if (character === '(') {
      depth += 1
    } else if (character === ')') {
      depth -= 1
      if (depth === 0) return index + 1
    }

    destinationStarted = true
  }

  return -1
}

function normalizeReferenceLabel(value) {
  return value.replace(/\\([!"#$%&'()*+,\-./:;<=>?@[\\\]^_`{|}~])/g, '$1').trim().replace(/\s+/g, ' ').toLowerCase()
}

function collectReferenceDefinitions(value, protectedRanges) {
  const definitions = new Map()

  for (const match of value.matchAll(referenceDefinition)) {
    if (findRangeContaining(protectedRanges, match.index)) continue

    const destination = match[2] ?? match[3]
    const label = normalizeReferenceLabel(match[1])

    if (definitions.has(label)) continue

    definitions.set(label, {
      start: match.index,
      end: match.index + match[0].length,
      unsafe: unsafeImageScheme.test(destination),
    })
  }

  return definitions
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

export function sanitizeMarkdownImageDestinations(value = '') {
  const protectedRanges = findProtectedMarkdownRanges(value)
  const referenceDefinitions = collectReferenceDefinitions(value, protectedRanges)
  const referencedDefinitions = new Set()
  const replacements = []
  let searchStart = 0

  while (searchStart < value.length) {
    const imageStart = value.indexOf('![', searchStart)
    if (imageStart === -1) break

    const protectedRange = findRangeContaining(protectedRanges, imageStart)
    if (protectedRange) {
      searchStart = protectedRange.end
      continue
    }

    if (isEscapedAt(value, imageStart)) {
      searchStart = imageStart + 2
      continue
    }

    const altEnd = findClosingDelimiter(value, imageStart + 1, '[', ']')
    if (altEnd === -1) break

    const alt = value.slice(imageStart + 2, altEnd)
    const nextCharacter = value[altEnd + 1]

    if (nextCharacter === '(') {
      let destinationStart = altEnd + 2
      while (/[ \t\n]/.test(value[destinationStart] ?? '')) destinationStart += 1

      const angleDestinationEnd = value[destinationStart] === '<'
        ? value.indexOf('>', destinationStart + 1)
        : -1
      const destination = angleDestinationEnd === -1
        ? value.slice(destinationStart)
        : value.slice(destinationStart + 1, angleDestinationEnd)

      if (unsafeImageScheme.test(destination)) {
        const imageEnd = findInlineImageEnd(value, altEnd + 1, angleDestinationEnd + 1)
        if (imageEnd !== -1) replacements.push({ start: imageStart, end: imageEnd, value: alt })
      }

      searchStart = altEnd + 2
      continue
    }

    let imageEnd = altEnd + 1
    let referenceLabel = alt

    if (nextCharacter === '[') {
      const referenceEnd = findClosingDelimiter(value, altEnd + 1, '[', ']')
      if (referenceEnd === -1) {
        searchStart = altEnd + 1
        continue
      }

      referenceLabel = value.slice(altEnd + 2, referenceEnd) || alt
      imageEnd = referenceEnd + 1
    }

    const normalizedLabel = normalizeReferenceLabel(referenceLabel)
    if (referenceDefinitions.get(normalizedLabel)?.unsafe) {
      referencedDefinitions.add(normalizedLabel)
      replacements.push({ start: imageStart, end: imageEnd, value: alt })
    }

    searchStart = imageEnd
  }

  for (const label of referencedDefinitions) {
    const definition = referenceDefinitions.get(label)
    let { start, end } = definition

    if (!value.slice(end).trim()) {
      while (start > 0 && /\s/.test(value[start - 1])) start -= 1
    } else {
      while (end < value.length && /\s/.test(value[end])) end += 1
    }

    replacements.push({ start, end, value: '' })
  }

  return removeRanges(value, replacements)
}

export function estimateReadingMinutes(value = '') {
  const characters = value.replace(/[`*_#>[\]()!-]/g, '').replace(/\s/g, '').length
  return Math.max(1, Math.ceil(characters / 400))
}
