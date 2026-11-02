import type {
  AppealConditions,
  AppealStatus,
  ChallengeAnswer,
  ChallengePurpose,
  ConversationPage,
  ErrorBody,
  ErrorCode,
  MessagePosted,
  PunishmentFacts,
  SessionInfo,
  StagedAttachment,
} from './contract'
import { CHAT_ID, CSRF_HEADER, IDEMPOTENCY_HEADER } from './contract'

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
  login(banId: string, altcha?: string): Promise<ApiResult<SessionInfo>>
  /** The challenge for sign-in or appeal, or `enabled: false` when the check is off. */
  challenge(purpose: ChallengePurpose): Promise<ApiResult<ChallengeAnswer>>
  session(): Promise<ApiResult<SessionInfo>>
  logout(): Promise<ApiResult<null>>
  punishment(): Promise<ApiResult<PunishmentFacts>>
  /** Files the appeal; `conditionsVersion` is the version of the conditions the player confirmed. */
  fileAppeal(statement: string, conditionsVersion: number, altcha?: string): Promise<ApiResult<AppealStatus>>
  appealConditions(): Promise<ApiResult<AppealConditions>>
  appeal(): Promise<ApiResult<AppealStatus>>
  conversation(cursor: number | null): Promise<ApiResult<ConversationPage>>
  /** Sends a message: text (optional when files are attached), the staged file ids, and an Idempotency-Key. */
  postMessage(text: string | undefined, idempotencyKey: string, attachmentIds?: string[]): Promise<ApiResult<MessagePosted>>
  /** Stages one chat file for the open appeal. Nothing is visible until a message sends it. */
  uploadAttachment(file: File): Promise<ApiResult<StagedAttachment>>
  /** Removes a staged file that no message has sent. */
  deleteAttachment(attachmentId: string): Promise<ApiResult<null>>
}

/** The URL a chat file is shown or downloaded from (same origin, through the BFF); null for an id that is not a plain token. */
export function attachmentUrl(id: string): string | null {
  return CHAT_ID.test(id) ? `/api/attachments/${id}` : null
}

/** The live stream of the conversation, on the BFF. */
export const EVENTS_URL = '/api/conversation/events'

export interface PortalApiOptions {
  fetch: typeof fetch
  /** The CSRF value of the current session, from memory. */
  csrf: () => string | null
}

function unavailable(status = 0): ApiResult<never> {
  return { ok: false, error: { status, code: 'UNAVAILABLE' } }
}

export function createPortalApi({ fetch: fetchImpl, csrf }: PortalApiOptions): PortalApi {
  async function call<T>(method: string, path: string, body?: unknown, extra: Record<string, string> = {}, form?: FormData): Promise<ApiResult<T>> {
    const headers: Record<string, string> = { accept: 'application/json', ...extra }
    // A form is sent as it is: the browser sets its multipart type and boundary.
    if (body !== undefined && form === undefined) headers['content-type'] = 'application/json'
    if (method !== 'GET') {
      const token = csrf()
      if (token) headers[CSRF_HEADER] = token
    }
    let response: Response
    try {
      response = await fetchImpl(path, {
        method,
        headers,
        body: form ?? (body === undefined ? undefined : JSON.stringify(body)),
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
    login: (banId, altcha) => call('POST', '/api/session', altcha === undefined ? { banId } : { banId, altcha }),
    challenge: (purpose) => call('GET', `/api/challenge?purpose=${purpose}`),
    session: () => call('GET', '/api/session'),
    logout: () => call('DELETE', '/api/session'),
    punishment: () => call('GET', '/api/punishment'),
    fileAppeal: (statement, conditionsVersion, altcha) =>
      call('POST', '/api/appeal', altcha === undefined ? { statement, conditionsVersion } : { statement, conditionsVersion, altcha }),
    appealConditions: () => call('GET', '/api/appeal/conditions'),
    appeal: () => call('GET', '/api/appeal'),
    conversation: (cursor) => call('GET', cursor === null ? '/api/conversation' : `/api/conversation?cursor=${cursor}`),
    postMessage: (text, key, attachmentIds) => {
      const body: { text?: string; attachmentIds?: string[] } = {}
      if (text !== undefined) body.text = text
      if (attachmentIds !== undefined && attachmentIds.length > 0) body.attachmentIds = attachmentIds
      return call('POST', '/api/messages', body, { [IDEMPOTENCY_HEADER]: key })
    },
    uploadAttachment: (file) => {
      const form = new FormData()
      form.append('file', file, file.name)
      return call('POST', '/api/attachments', undefined, {}, form)
    },
    deleteAttachment: (attachmentId) => {
      if (!CHAT_ID.test(attachmentId)) return Promise.resolve({ ok: false, error: { status: 404, code: 'NOT_FOUND' } })
      return call('DELETE', `/api/attachments/${attachmentId}`)
    },
  }
}
