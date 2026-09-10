// 只把稳定错误码翻译成可操作的提示；不展示上游原文、异常消息或未知字段。
const messages = {
  AGENT_CAPACITY_EXHAUSTED: '当前服务繁忙，生成名额已满，请稍后重试。',
  AGENT_STREAM_CAPACITY_EXHAUSTED: '实时连接名额已满，正在尝试恢复原回答，请勿重复发送。',
  AI_CONCURRENCY_LIMIT: '模型调用名额已满，请稍后重试。',
  AI_QUOTA_EXCEEDED: '当前 AI 使用额度或请求频率已达上限，请稍后重试或检查额度。',
  ACTIVE_TURN_EXISTS: '上一轮回答还在进行，请等待完成或先停止回答。',
  TEMPORARY_SESSION_EXPIRED: '临时对话已过期，请重新开启。',
  REACT_INVALID_JSON: '模型返回的决策格式不正确，本轮已停止，可以重试。',
  REACT_INVALID_SHAPE: '模型返回的决策字段不符合协议，本轮已停止，可以重试。',
  REACT_UNKNOWN_TOOL: '模型选择了系统不支持的工具，本轮已停止，可以重试。',
  REACT_FORBIDDEN_TOOL: '模型选择的工具超出当前权限，系统已拒绝执行。',
  REACT_INVALID_QUERY: '模型生成的检索参数不合规，本轮已停止，可以重试。',
  REACT_RESPONSE_TRUNCATED: '模型决策响应被截断，本轮已停止，可以重试。',
  REACT_ROUTE_MISMATCH: '模型返回的执行路径不符合当前协议，本轮已停止。',
  REACT_RESPONSE_TOO_LARGE: '模型决策响应超过大小限制，本轮已停止。',
  REACT_RESPONSE_INCOMPLETE: '模型决策响应不完整，本轮已停止，可以重试。',
  REACT_INVALID_RESPONSE: '模型没有返回有效决策，本轮已停止，可以重试。',
}

export function agentErrorMessage(source) {
  const code = typeof source === 'string' ? source : source?.response?.data?.code || source?.code || source?.error
  return Object.hasOwn(messages, code) ? messages[code] : '这次回答没有完成，请稍后重试。'
}
