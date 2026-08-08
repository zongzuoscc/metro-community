export function normalizeMarkdown(value = '') {
  return value.replace(/[ \t]+$/gm, '').replace(/\n{3,}/g, '\n\n').trim()
}

const unsafeImageScheme = /^(?:data|blob):/i
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

function collectReferenceDefinitions(value) {
  const definitions = new Map()

  for (const match of value.matchAll(referenceDefinition)) {
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
  const referenceDefinitions = collectReferenceDefinitions(value)
  const referencedDefinitions = new Set()
  const replacements = []
  let searchStart = 0

  while (searchStart < value.length) {
    const imageStart = value.indexOf('![', searchStart)
    if (imageStart === -1) break

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
