import { describe, expect, it } from 'vitest'
import { agentErrorMessage } from './agentErrors'

describe('Agent 安全错误提示', () => {
  it('区分容量、额度、ReAct 协议和权限问题', () => {
    expect(agentErrorMessage({ response: { data: { code: 'AGENT_CAPACITY_EXHAUSTED' } } })).toContain('繁忙')
    expect(agentErrorMessage('REACT_INVALID_JSON')).toContain('格式')
    expect(agentErrorMessage({ code: 'REACT_FORBIDDEN_TOOL' })).toContain('权限')
    expect(agentErrorMessage({ error: 'REACT_RESPONSE_TRUNCATED' })).toContain('截断')
  })
  it('未知错误不展示异常原文或上游敏感内容', () => {
    expect(agentErrorMessage({ error: 'secret-upstream-body' })).not.toContain('secret')
    expect(agentErrorMessage(new Error('api-key-secret'))).not.toContain('secret')
  })
})
