/**
 * The whole contract of the portal in one place: the routes the browser may call on the BFF, the
 * backend `/public/**` routes each one maps to, and the payload shapes of both sides.
 *
 * Source: openspec/changes/add-appeal-portal (design decisions 4, 6, 7, 10; specs appeal-portal and
 * ban-id). The backend side is built in a separate change, so everything the backend sends is
 * validated or whitelisted by the BFF and everything that may need adjusting lives here.
 *
 * The BFF is the mirror of the backend: `/api/<name>` maps one to one onto `/public/<name>`.
 * Pure data and types, so the browser bundle and the server bundle can both import it.
 */

export type PlayerScope = 'FULL' | 'LIMITED'

/** `appeal.state` of the punishment facts (design decision 4a). */
export type AppealState = 'NOT_YET' | 'AVAILABLE' | 'OPEN' | 'DECIDED' | 'COOLDOWN' | 'FORFEITED' | 'FROZEN'

export type PunishmentState = 'ACTIVE' | 'EXPIRED' | 'LIFTED' | 'REVOKED'

/** `POST /api/session` request. The Ban ID travels in this body and nowhere else. */
export interface LoginRequest {
  banId: string
}

/** What the browser gets for login and for `GET /api/session`. The session token never reaches it. */
export interface SessionInfo {
  /** Sent back as `X-CSRF-Token` on every POST and DELETE. Kept in memory only. */
  csrf: string
  expiresAt: string
  scope: PlayerScope
}

/** What the backend returns for `POST /public/session`; the BFF strips `token` into the cookie. */
export interface BackendLogin extends SessionInfo {
  token: string
}

export interface PunishmentFacts {
  /** Pseudonymous label of the case, never a hash or short id. */
  reference: string
  type: string
  reason: string
  section: string
  createdAt: string
  permanent: boolean
  expiresAt: string | null
  state: PunishmentState
  evidenceCount: number
  appeal: { state: AppealState; availableFrom: string | null }
}

export interface AppealStatus {
  /** Appeal status, e.g. `ELIGIBLE_PENDING_REVIEW`, `DENIED`, `GRANTED_LIFT`. Shown as a label, unknown values fall back. */
  status: string
  /** Null until decided. */
  decision: string | null
  /** For a grant that shortens the punishment: the new expiry. */
  newExpiresAt: string | null
  filedAt: string | null
}

export interface AppealRequest {
  statement: string
}

export type ConversationAuthor = 'PLAYER' | 'STAFF' | 'SYSTEM'

/** One entry of the player-visible conversation (allow-listed types only, paged by `seq`). */
export interface ConversationEntry {
  seq: number
  occurredAt: string
  /** `MESSAGE` for a text, or a marker such as `APPEAL_FILED`, `APPEAL_DECIDED`, `FROZEN`, `TICKET_OPENED`, `TICKET_CLOSED`. */
  kind: string
  author: ConversationAuthor
  messageId: string | null
  /** The current text (edits are folded in by the backend). Null for a removed message and for markers. */
  text: string | null
  /** A deleted or redacted message: shown as "message removed", without text. */
  removed: boolean
}

export interface ConversationPage {
  entries: ConversationEntry[]
  /** The `seq` to pass as `cursor` next time; null when there is nothing yet. */
  cursor: number | null
}

export interface MessageRequest {
  text: string
}

/** Fixed set of error codes the BFF answers; the browser never sees a raw backend error. */
export type ErrorCode =
  | 'INVALID_BAN_ID'
  | 'SESSION_EXPIRED'
  | 'FORBIDDEN'
  | 'NOT_FOUND'
  | 'VALIDATION'
  | 'PII_REJECTED'
  | 'FORFEITED'
  | 'FROZEN'
  | 'NOT_ELIGIBLE'
  | 'NO_APPEAL'
  | 'NOT_DECIDED'
  | 'CONFLICT'
  | 'TOO_LARGE'
  | 'UNAVAILABLE'
  | 'CSRF'
  | 'ORIGIN'
  | 'BAD_REQUEST'

export interface ErrorBody {
  code: ErrorCode
  /** Only on `NOT_ELIGIBLE`: when the player may file (ISO instant). */
  availableFrom?: string
}

/** Backend 409 `code` values the BFF passes through; anything else becomes `CONFLICT`. */
export const CONFLICT_CODES = ['FORFEITED', 'FROZEN', 'NOT_ELIGIBLE', 'NO_APPEAL', 'NOT_DECIDED'] as const

export interface RouteSpec {
  /** Browser-facing path on the BFF. */
  path: string
  method: 'GET' | 'POST' | 'DELETE'
  /** Backend path (never contains an identifier: the punishment comes from the session). */
  backend: string
  /** Needs the session cookie. Only the login does not. */
  session: boolean
  /** Query parameters forwarded, each with its allowed shape. */
  query?: Record<string, RegExp>
  /** Body fields forwarded (everything else is dropped); present for routes with a JSON body. */
  body?: readonly string[]
}

const CURSOR = /^[0-9]{1,18}$/

export const ROUTES: readonly RouteSpec[] = [
  { path: '/api/session', method: 'POST', backend: '/public/session', session: false, body: ['banId'] },
  { path: '/api/session', method: 'GET', backend: '/public/session', session: true },
  { path: '/api/session', method: 'DELETE', backend: '/public/session', session: true },
  { path: '/api/punishment', method: 'GET', backend: '/public/punishment', session: true },
  { path: '/api/appeal', method: 'POST', backend: '/public/appeal', session: true, body: ['statement'] },
  { path: '/api/appeal', method: 'GET', backend: '/public/appeal', session: true },
  { path: '/api/appeal/protocol', method: 'GET', backend: '/public/appeal/protocol', session: true, query: { format: /^(text|json)$/ } },
  { path: '/api/conversation', method: 'GET', backend: '/public/conversation', session: true, query: { cursor: CURSOR } },
  { path: '/api/messages', method: 'POST', backend: '/public/messages', session: true, body: ['text'] },
]

export const SESSION_COOKIE = '__Host-bh_portal'
export const CSRF_COOKIE = '__Host-bh_csrf'
export const CSRF_HEADER = 'x-csrf-token'
export const IDEMPOTENCY_HEADER = 'idempotency-key'

/** Same limit for statement and message (design decision 5); the backend is authoritative. */
export const MAX_TEXT_LENGTH = 2000
/** The BFF caps request bodies at 16 KiB (design decision 5). */
export const MAX_BODY_BYTES = 16 * 1024
