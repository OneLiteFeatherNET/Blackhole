import type {
  AppealStatus,
  ConversationPage,
  ErrorBody,
  ErrorCode,
  PunishmentFacts,
  SessionInfo,
} from './contract'
import { CSRF_HEADER, IDEMPOTENCY_HEADER } from './contract'

/**
 * The typed client of the browser for the BFF (`/api/**`, see contract.ts). Nothing here touches
 * browser storage; the session token is an HttpOnly cookie the page cannot read, and the CSRF value
 * is handed in by the caller from memory.
 */

export interface ApiError {
  status: number
  code: ErrorCode
  availableFrom?: string
}

export type ApiResult<T> = { ok: true; data: T } | { ok: false; error: ApiError }

export interface PortalApi {
  login(banId: string): Promise<ApiResult<SessionInfo>>
  session(): Promise<ApiResult<SessionInfo>>
  logout(): Promise<ApiResult<null>>
  punishment(): Promise<ApiResult<PunishmentFacts>>
  fileAppeal(statement: string): Promise<ApiResult<AppealStatus>>
  appeal(): Promise<ApiResult<AppealStatus>>
  conversation(cursor: number | null): Promise<ApiResult<ConversationPage>>
  postMessage(text: string, idempotencyKey: string): Promise<ApiResult<null>>
}

export interface PortalApiOptions {
  fetch: typeof fetch
  /** The CSRF value of the current session, from memory. */
  csrf: () => string | null
}

function unavailable(status = 0): ApiResult<never> {
  return { ok: false, error: { status, code: 'UNAVAILABLE' } }
}

export function createPortalApi({ fetch: fetchImpl, csrf }: PortalApiOptions): PortalApi {
  async function call<T>(method: string, path: string, body?: unknown, extra: Record<string, string> = {}): Promise<ApiResult<T>> {
    const headers: Record<string, string> = { accept: 'application/json', ...extra }
    if (body !== undefined) headers['content-type'] = 'application/json'
    if (method !== 'GET') {
      const token = csrf()
      if (token) headers[CSRF_HEADER] = token
    }
    let response: Response
    try {
      response = await fetchImpl(path, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        credentials: 'same-origin',
        cache: 'no-store',
        redirect: 'error',
      })
    } catch {
      return unavailable()
    }
    if (response.ok) {
      if (response.status === 204) return { ok: true, data: null as T }
      try {
        return { ok: true, data: (await response.json()) as T }
      } catch {
        return unavailable(response.status)
      }
    }
    let parsed: Partial<ErrorBody> = {}
    try {
      parsed = (await response.json()) as Partial<ErrorBody>
    } catch {
      // An answer without a body (a proxy error page) is treated as unavailable below.
    }
    const code = typeof parsed.code === 'string' ? (parsed.code as ErrorCode) : 'UNAVAILABLE'
    const availableFrom = typeof parsed.availableFrom === 'string' ? { availableFrom: parsed.availableFrom } : {}
    return { ok: false, error: { status: response.status, code, ...availableFrom } }
  }

  return {
    login: (banId) => call('POST', '/api/session', { banId }),
    session: () => call('GET', '/api/session'),
    logout: () => call('DELETE', '/api/session'),
    punishment: () => call('GET', '/api/punishment'),
    fileAppeal: (statement) => call('POST', '/api/appeal', { statement }),
    appeal: () => call('GET', '/api/appeal'),
    conversation: (cursor) => call('GET', cursor === null ? '/api/conversation' : `/api/conversation?cursor=${cursor}`),
    postMessage: (text, key) => call('POST', '/api/messages', { text }, { [IDEMPOTENCY_HEADER]: key }),
  }
}
