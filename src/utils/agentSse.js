/**
 * 创建一个可增量喂入网络分片的 SSE 解析器。
 *
 * 浏览器 fetch 的 ReadableStream 不保证每次读取都落在事件边界，JSON 甚至可能被拆成多个字节块。
 * 解析器因此保留最后一个未完成片段，只在读到空行时交付完整事件。
 */
export function createAgentSseParser(onEvent) {
  let buffer = ''
  let pendingCarriageReturn = false

  const dispatch = block => {
    let id = ''
    let type = 'message'
    const dataLines = []

    for (const line of block.split('\n')) {
      // 以冒号开头的是 SSE 心跳或注释，不属于业务事件。
      if (!line || line.startsWith(':')) continue
      const separator = line.indexOf(':')
      const field = separator < 0 ? line : line.slice(0, separator)
      let value = separator < 0 ? '' : line.slice(separator + 1)
      if (value.startsWith(' ')) value = value.slice(1)

      if (field === 'id') id = value
      else if (field === 'event') type = value || 'message'
      else if (field === 'data') dataLines.push(value)
    }

    if (dataLines.length === 0) return
    const rawData = dataLines.join('\n')
    let data = rawData
    try {
      data = JSON.parse(rawData)
    } catch {
      // 标准 SSE 允许非 JSON data。保留原文比吞掉事件或伪造结构更安全。
    }
    onEvent({ id, type, data })
  }

  const drain = flushTail => {
    // 同时兼容 CRLF 与 LF；先规范化换行，避免 Windows 风格帧无法识别空行。
    buffer = buffer.replaceAll('\r\n', '\n').replaceAll('\r', '\n')
    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const block = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      dispatch(block)
      boundary = buffer.indexOf('\n\n')
    }
    if (flushTail && buffer.trim()) {
      dispatch(buffer)
      buffer = ''
    }
  }

  return {
    push(chunk) {
      let incoming = chunk
      // CRLF 可以被网络分在两次 read 中。块尾的单独 CR 要延迟到下一块再判定，
      // 否则会把下一块开头的 LF 误解为第二个换行，提前切断多行 data 事件。
      if (pendingCarriageReturn) {
        incoming = `\r${incoming}`
        pendingCarriageReturn = false
      }
      if (incoming.endsWith('\r')) {
        incoming = incoming.slice(0, -1)
        pendingCarriageReturn = true
      }
      buffer += incoming
      drain(false)
    },
    finish() {
      if (pendingCarriageReturn) {
        buffer += '\r'
        pendingCarriageReturn = false
      }
      drain(true)
    },
  }
}
