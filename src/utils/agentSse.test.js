import { describe, expect, it } from 'vitest'

import { createAgentSseParser } from './agentSse'

describe('Agent SSE 增量解析', () => {
  it('preserves an event split across network chunks and parses the following terminal event', () => {
    const events = []
    const parser = createAgentSseParser(event => events.push(event))

    parser.push('id: 1-0\nevent: accepted\ndata: {"turnId":-1,"type":"accepted","pay')
    parser.push('load":{"state":"RUNNING"}}\n\nid: 2-0\nevent: done\ndata: {"turnId":-1,"type":"done","payload":{"finalMessage":"回答完成"}}\n\n')
    parser.finish()

    expect(events).toEqual([
      {
        id: '1-0',
        type: 'accepted',
        data: { turnId: -1, type: 'accepted', payload: { state: 'RUNNING' } },
      },
      {
        id: '2-0',
        type: 'done',
        data: { turnId: -1, type: 'done', payload: { finalMessage: '回答完成' } },
      },
    ])
  })

  it('ignores comments and preserves non-JSON data without inventing a payload', () => {
    const events = []
    const parser = createAgentSseParser(event => events.push(event))

    parser.push(': keep-alive\nid: 3-0\nevent: delta\ndata: 第一行\ndata: 第二行\n\n')
    parser.finish()

    expect(events).toEqual([{
      id: '3-0',
      type: 'delta',
      data: '第一行\n第二行',
    }])
  })

  it('keeps a CRLF sequence intact when the carriage return and line feed arrive in different chunks', () => {
    const events = []
    const parser = createAgentSseParser(event => events.push(event))

    parser.push('id: 4-0\r\nevent: delta\r\ndata: 第一行\r')
    parser.push('\ndata: 第二行\r\n\r\n')
    parser.finish()

    expect(events).toEqual([{
      id: '4-0',
      type: 'delta',
      data: '第一行\n第二行',
    }])
  })
})
