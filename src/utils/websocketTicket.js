const DEFAULT_API_BASE_URL = 'http://localhost:18080'

export class WebSocketTicketRequestError extends Error {
  constructor(message, status = null) {
    super(message)
    this.name = 'WebSocketTicketRequestError'
    this.status = status
  }
}

export const requestWebSocketTicket = async (
  token,
  {
    signal,
    backendBaseUrl = import.meta.env.VITE_API_BASE_URL || DEFAULT_API_BASE_URL,
    pageOrigin = window.location.origin
  } = {}
) => {
  if (!token) throw new WebSocketTicketRequestError('Login token is required', 401)

  const endpoint = new URL(backendBaseUrl, pageOrigin)
  endpoint.pathname = '/api/ws/ticket'
  endpoint.search = ''
  endpoint.hash = ''

  const response = await fetch(endpoint.toString(), {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` },
    signal
  })

  let payload = null
  try {
    payload = await response.json()
  } catch {
    // The status remains sufficient to classify authentication and retry behavior.
  }

  if (!response.ok || payload?.code !== 200) {
    throw new WebSocketTicketRequestError(
      payload?.msg || '无法申请实时连接凭证',
      response.status
    )
  }

  const ticket = payload?.data?.ticket
  if (typeof ticket !== 'string' || !ticket.trim()) {
    throw new WebSocketTicketRequestError('实时连接凭证响应无效', response.status)
  }
  return ticket
}
