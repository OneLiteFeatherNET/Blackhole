import { timingSafeEqual } from 'node:crypto'
import {
  deleteCookie,
  eventHandler,
  getCookie,
  getHeader,
  getQuery,
  getRequestIP,
  getRequestWebStream,
  readRawBody,
  send,
  setCookie,
  setResponseHeader,
  setResponseStatus,
  type H3Event,
} from 'h3'
import {
  CONFLICT_CODES,
  CSRF_COOKIE,
  CSRF_HEADER,
  FILE_REJECTION_CODES,
  IDEMPOTENCY_HEADER,
  MAX_BODY_BYTES,
  MAX_FILES,
  MAX_UPLOAD_BYTES,
  ROUTES,
  SESSION_COOKIE,
  UUID_SHAPE,
  type BackendLogin,
  type ErrorBody,
  type ErrorCode,
  type RouteSpec,
  type StagedAttachment,
} from '../../src/api/contract'
import type { PortalConfig } from './config'
import { limitBytes, relayBody, TooLargeError } from './streams'

/**
 * The browser-facing half of the portal: a backend-for-frontend with an allow-list of exactly the
 * routes in `ROUTES`. Everything else answers 404 without touching the backend. The session token
 * lives only in an HttpOnly cookie; request and response bodies are never logged.
 */

/** The cookie flags of the design (decision 7): `__Host-` needs Secure, Path=/ and no Domain. */
export const COOKIE_SAME_SITE = 'lax' as const
export const BACKEND_TIMEOUT_MS = 15_000
const ABSOLUTE_SESSION_SECONDS = 4 * 60 * 60
const TOKEN_SHAPE = /^[A-Za-z0-9._~+/=-]{16,512}$/
const CSRF_SHAPE = /^[A-Za-z0-9._~+/=-]{8,256}$/
const FORWARDED_TYPES = /^(application\/json|text\/plain)(;|$)/i
const IDEMPOTENCY_SHAPE = /^[A-Za-z0-9-]{8,64}$/
const LAST_EVENT_ID_SHAPE = /^[0-9]{1,18}$/
const MULTIPART_TYPE = /^multipart\/form-data;\s*boundary=/i
/** Types a chat file download may carry; anything else from the backend is refused. */
const DOWNLOAD_TYPES = new Set(['image/png', 'image/jpeg', 'image/webp', 'application/pdf', 'text/plain'])
const INLINE_TYPES = new Set(['image/png', 'image/jpeg', 'image/webp'])

interface Declared {
  config: PortalConfig
  fetchImpl: typeof fetch
}

function respond(event: H3Event, status: number, body?: unknown) {
  setResponseHeader(event, 'Cache-Control', 'no-store')
  setResponseStatus(event, status)
  if (body === undefined) return send(event, '')
  setResponseHeader(event, 'Content-Type', 'application/json; charset=utf-8')
  return send(event, JSON.stringify(body))
}

function fail(event: H3Event, status: number, code: ErrorCode, extra: Partial<ErrorBody> = {}) {
  return respond(event, status, { code, ...extra } satisfies ErrorBody)
}

function cookieOptions(maxAge: number) {
  return { httpOnly: true, secure: true, sameSite: COOKIE_SAME_SITE, path: '/', maxAge } as const
}

function clearSession(event: H3Event) {
  for (const name of [SESSION_COOKIE, CSRF_COOKIE]) deleteCookie(event, name, { httpOnly: true, secure: true, sameSite: COOKIE_SAME_SITE, path: '/' })
}

/** The route for a method and a path, with the `:id` segment of a parameterised route. */
function matchRoute(method: string, pathname: string): { route: RouteSpec; id?: string } | undefined {
  for (const route of ROUTES) {
    if (route.method !== method) continue
    if (route.param) {
      const prefix = route.path.slice(0, -':id'.length)
      if (!pathname.startsWith(prefix)) continue
      const id = pathname.slice(prefix.length)
      if (route.param.shape.test(id)) return { route, id }
      continue
    }
    if (route.path === pathname) return { route }
  }
  return undefined
}

function sameSecret(a: string | undefined, b: string | undefined): boolean {
  if (!a || !b) return false
  const left = Buffer.from(a)
  const right = Buffer.from(b)
  return left.length === right.length && timingSafeEqual(left, right)
}

/** Seconds until `expiresAt`, never beyond the absolute session limit, at least 1. */
function cookieLifetime(expiresAt: unknown, now: number): number {
  const at = typeof expiresAt === 'string' ? Date.parse(expiresAt) : Number.NaN
  if (Number.isNaN(at)) return ABSOLUTE_SESSION_SECONDS
  return Math.min(ABSOLUTE_SESSION_SECONDS, Math.max(1, Math.floor((at - now) / 1000)))
}

/**
 * The fixed error set of a chat file route. A 409 and a 422 carry the backend's own code, but only
 * the codes of this set; everything else on those statuses is a plain conflict or an invalid file.
 */
export function mapFileError(status: number, body: unknown): { status: number; error: ErrorBody } {
  const record = typeof body === 'object' && body !== null ? (body as Record<string, unknown>) : {}
  const code = String(record.code)
  if (status === 415) return { status: 415, error: { code: 'UNSUPPORTED_TYPE' } }
  if (status === 422) {
    const rejection = (FILE_REJECTION_CODES as readonly string[]).includes(code) ? (code as ErrorCode) : 'INVALID_FILE'
    return { status: 422, error: { code: rejection } }
  }
  if (status === 409) {
    return { status: 409, error: { code: (CONFLICT_CODES as readonly string[]).includes(code) ? (code as ErrorCode) : 'CONFLICT' } }
  }
  return mapBackendError(status, body)
}

/** The fixed error set of the portal: what a backend status means to the browser. Never echoes backend text. */
export function mapBackendError(status: number, body: unknown): { status: number; error: ErrorBody } {
  const record = typeof body === 'object' && body !== null ? (body as Record<string, unknown>) : {}
  switch (true) {
    case status === 401:
      return { status: 401, error: { code: 'SESSION_EXPIRED' } }
    case status === 403:
      return { status: 403, error: { code: 'FORBIDDEN' } }
    case status === 404:
      return { status: 404, error: { code: 'NOT_FOUND' } }
    case status === 409: {
      const code = (CONFLICT_CODES as readonly string[]).includes(String(record.code)) ? (record.code as ErrorCode) : 'CONFLICT'
      const available = typeof record.availableFrom === 'string' && !Number.isNaN(Date.parse(record.availableFrom)) ? { availableFrom: record.availableFrom } : {}
      return { status: 409, error: { code, ...available } }
    }
    case status === 413:
      return { status: 413, error: { code: 'TOO_LARGE' } }
    case status === 422:
      return { status: 422, error: { code: 'PII_REJECTED' } }
    case status >= 400 && status < 500:
      return { status: 400, error: { code: 'VALIDATION' } }
    default:
      return { status: 503, error: { code: 'UNAVAILABLE' } }
  }
}

async function parseJson(response: Response): Promise<unknown> {
  try {
    return JSON.parse(await response.text())
  } catch {
    return undefined
  }
}

type BodyResult = { ok: true; json?: string } | { ok: false; status: number; code: ErrorCode }

async function readBody(event: H3Event, route: RouteSpec): Promise<BodyResult> {
  if (!route.body) return { ok: true }
  const declared = Number(getHeader(event, 'content-length'))
  if (Number.isFinite(declared) && declared > MAX_BODY_BYTES) return { ok: false, status: 413, code: 'TOO_LARGE' }
  const raw = await readRawBody(event, 'utf8')
  if (raw === undefined || Buffer.byteLength(raw) > MAX_BODY_BYTES) {
    return raw === undefined ? { ok: false, status: 400, code: 'BAD_REQUEST' } : { ok: false, status: 413, code: 'TOO_LARGE' }
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return { ok: false, status: 400, code: 'BAD_REQUEST' }
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return { ok: false, status: 400, code: 'BAD_REQUEST' }
  const source = parsed as Record<string, unknown>
  const picked: Record<string, string | number> = {}
  for (const field of route.body) {
    const value = source[field]
    if (typeof value !== 'string') return { ok: false, status: 400, code: 'BAD_REQUEST' }
    picked[field] = value
  }
  for (const field of route.integers ?? []) {
    const value = source[field]
    if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 1 || value > 2_147_483_647) return { ok: false, status: 400, code: 'BAD_REQUEST' }
    picked[field] = value
  }
  const forwarded: Record<string, unknown> = { ...picked }
  for (const field of route.optionalBody ?? []) {
    const value = source[field]
    if (value === undefined || value === null) continue
    if (typeof value !== 'string') return { ok: false, status: 400, code: 'BAD_REQUEST' }
    forwarded[field] = value
  }
  for (const field of route.uuidLists ?? []) {
    const value = source[field]
    if (value === undefined || value === null) continue
    const valid = Array.isArray(value) && value.length <= MAX_FILES && value.every((item) => typeof item === 'string' && UUID_SHAPE.test(item))
    if (!valid) return { ok: false, status: 400, code: 'BAD_REQUEST' }
    forwarded[field] = value
  }
  return { ok: true, json: JSON.stringify(forwarded) }
}

function backendQuery(event: H3Event, route: RouteSpec): URLSearchParams | undefined {
  const query = new URLSearchParams()
  const incoming = getQuery(event)
  for (const [name, shape] of Object.entries(route.query ?? {})) {
    const value = incoming[name]
    if (value === undefined) continue
    if (typeof value !== 'string' || !shape.test(value)) return undefined
    query.set(name, value)
  }
  return query
}

/** Handles `/api/**`: the one place the browser reaches the backend through. */
export function createBffHandler({ config, fetchImpl }: Declared) {
  return eventHandler(async (event) => {
    const pathname = event.path.split('?')[0] ?? ''
    const matched = matchRoute(event.method, pathname)
    if (!matched) return fail(event, 404, 'NOT_FOUND')
    const { route, id } = matched

    const changesState = route.method !== 'GET'
    if (changesState && getHeader(event, 'origin') !== config.portalOrigin) return fail(event, 403, 'ORIGIN')

    const token = getCookie(event, SESSION_COOKIE)
    if (route.session && !token) {
      // Logging out without a session is already done.
      if (route.logout) {
        clearSession(event)
        return respond(event, 204)
      }
      clearSession(event)
      // A stream answers a bare 401: a native EventSource does not retry a refused connection.
      return route.stream ? respond(event, 401) : fail(event, 401, 'SESSION_EXPIRED')
    }
    if (route.session && changesState && !sameSecret(getHeader(event, CSRF_HEADER), getCookie(event, CSRF_COOKIE))) {
      return fail(event, 403, 'CSRF')
    }

    if (route.upload) return relayUpload(event, { config, fetchImpl, token: token ?? '' })

    const query = backendQuery(event, route)
    if (!query) return fail(event, 400, 'BAD_REQUEST')
    const body = await readBody(event, route)
    if (!body.ok) return fail(event, body.status, body.code)

    const headers = new Headers({ accept: backendAccept(route) })
    if (body.json !== undefined) headers.set('content-type', 'application/json')
    if (route.session && token) headers.set('authorization', `Bearer ${token}`)
    const client = getRequestIP(event, { xForwardedFor: true })
    if (client) headers.set('x-forwarded-for', client)
    const idempotency = getHeader(event, IDEMPOTENCY_HEADER)
    if (idempotency && IDEMPOTENCY_SHAPE.test(idempotency) && changesState) headers.set(IDEMPOTENCY_HEADER, idempotency)
    const lastEventId = getHeader(event, 'last-event-id')
    if (route.stream && lastEventId && LAST_EVENT_ID_SHAPE.test(lastEventId)) headers.set('last-event-id', lastEventId)

    const search = query.size > 0 ? `?${query.toString()}` : ''
    const backendPath = id === undefined ? route.backend : route.backend.replace(':id', encodeURIComponent(id))
    // A stream has no time limit of its own: it ends when either side goes away.
    const abort = new AbortController()
    let upstream: Response
    try {
      upstream = await fetchImpl(`${config.backendUrl}${backendPath}${search}`, {
        method: route.method,
        headers,
        body: body.json,
        redirect: 'manual',
        signal: route.stream ? abort.signal : AbortSignal.timeout(BACKEND_TIMEOUT_MS),
      })
    } catch {
      if (route.logout) clearSession(event)
      return fail(event, 503, 'UNAVAILABLE')
    }

    if (route.method === 'POST' && !route.session) return finishLogin(event, upstream)
    if (route.logout) {
      clearSession(event)
      return respond(event, 204)
    }
    if (!upstream.ok) {
      const mapped = route.download ? mapFileError(upstream.status, await parseJson(upstream)) : mapBackendError(upstream.status, await parseJson(upstream))
      if (mapped.status === 401) clearSession(event)
      return respond(event, mapped.status, mapped.error)
    }
    if (route.stream) return relayEvents(event, upstream, abort)
    if (route.download) return relayDownload(event, upstream)
    if (route.path === '/api/appeal/protocol') return relayFile(event, upstream)
    if (upstream.status === 204) return respond(event, 204)
    const json = await parseJson(upstream)
    if (json === undefined) return fail(event, 503, 'UNAVAILABLE')
    return respond(event, 200, json)
  })
}

function backendAccept(route: RouteSpec): string {
  if (route.stream) return 'text/event-stream'
  if (route.download) return '*/*'
  return route.path === '/api/appeal/protocol' ? 'text/plain, application/json' : 'application/json'
}

/** Keeps only the staged-file fields the browser needs; anything else the backend sends is dropped. */
function stagedOf(json: unknown): StagedAttachment | undefined {
  if (typeof json !== 'object' || json === null) return undefined
  const source = json as Record<string, unknown>
  const dimension = (value: unknown) => (typeof value === 'number' && Number.isSafeInteger(value) ? value : null)
  if (typeof source.attachmentId !== 'string' || !UUID_SHAPE.test(source.attachmentId)) return undefined
  if (typeof source.name !== 'string' || typeof source.contentType !== 'string' || !Number.isSafeInteger(source.size)) return undefined
  return {
    attachmentId: source.attachmentId,
    name: source.name,
    contentType: source.contentType,
    size: source.size as number,
    width: dimension(source.width),
    height: dimension(source.height),
  }
}

/**
 * A chat file upload: the browser's multipart body goes to the backend as it arrives. The declared
 * length is checked first and the bytes are counted on the way; an overflow cancels the upload.
 * One file per request. The five-files-per-message limit is the composer's and the backend's quota.
 */
async function relayUpload(event: H3Event, { config, fetchImpl, token }: Declared & { token: string }) {
  const type = getHeader(event, 'content-type') ?? ''
  if (!MULTIPART_TYPE.test(type)) return fail(event, 400, 'BAD_REQUEST')
  const declared = Number(getHeader(event, 'content-length'))
  if (!Number.isSafeInteger(declared) || declared < 0) return fail(event, 400, 'BAD_REQUEST')
  if (declared > MAX_UPLOAD_BYTES) return fail(event, 413, 'TOO_LARGE')
  const source = getRequestWebStream(event)
  if (!source) return fail(event, 400, 'BAD_REQUEST')

  const headers = new Headers({ accept: 'application/json', authorization: `Bearer ${token}`, 'content-type': type, 'content-length': String(declared) })
  const client = getRequestIP(event, { xForwardedFor: true })
  if (client) headers.set('x-forwarded-for', client)
  let upstream: Response
  try {
    upstream = await fetchImpl(`${config.backendUrl}/public/attachments`, {
      method: 'POST',
      headers,
      body: limitBytes(source, MAX_UPLOAD_BYTES),
      duplex: 'half',
      redirect: 'manual',
      signal: AbortSignal.timeout(BACKEND_TIMEOUT_MS),
    } as RequestInit)
  } catch (error) {
    // A stream that errors makes fetch reject with the error as its cause, so both places are looked at.
    const cause = (error as { cause?: unknown } | undefined)?.cause
    const overflow = error instanceof TooLargeError || cause instanceof TooLargeError
    return overflow ? fail(event, 413, 'TOO_LARGE') : fail(event, 503, 'UNAVAILABLE')
  }
  if (!upstream.ok) {
    const mapped = mapFileError(upstream.status, await parseJson(upstream))
    if (mapped.status === 401) clearSession(event)
    return respond(event, mapped.status, mapped.error)
  }
  const staged = stagedOf(await parseJson(upstream))
  return staged ? respond(event, 200, staged) : fail(event, 503, 'UNAVAILABLE')
}

/**
 * A chat file: an image is shown inline on the same origin, anything else is an attachment. The
 * sandbox policy and `no-store` apply to both, and a type outside the allow-list is refused.
 */
function relayDownload(event: H3Event, upstream: Response) {
  const type = (upstream.headers.get('content-type') ?? '').split(';')[0]!.trim().toLowerCase()
  if (!DOWNLOAD_TYPES.has(type) || !upstream.body) return fail(event, 503, 'UNAVAILABLE')
  return new Response(upstream.body, {
    status: 200,
    headers: {
      'Content-Type': type,
      'Content-Disposition': INLINE_TYPES.has(type) ? 'inline' : 'attachment',
      'Content-Security-Policy': 'sandbox',
      'Cache-Control': 'private, no-store',
      'X-Content-Type-Options': 'nosniff',
    },
  })
}

/**
 * The live stream, passed through as it arrives. Only a `text/event-stream` answer is relayed; the
 * upstream request is aborted when the browser goes away. Nothing is compressed or buffered.
 */
function relayEvents(event: H3Event, upstream: Response, abort: AbortController) {
  const type = upstream.headers.get('content-type') ?? ''
  if (!/^text\/event-stream(;|$)/i.test(type) || !upstream.body) return fail(event, 503, 'UNAVAILABLE')
  return new Response(relayBody(upstream.body, abort), {
    status: 200,
    headers: {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-store',
      'X-Accel-Buffering': 'no',
    },
  })
}

/** Login: every failure is the same answer; a good answer turns the token into cookies and never reaches the page. */
async function finishLogin(event: H3Event, upstream: Response) {
  if (upstream.status >= 500) return fail(event, 503, 'UNAVAILABLE')
  if (!upstream.ok) return fail(event, 401, 'INVALID_BAN_ID')
  const login = (await parseJson(upstream)) as Partial<BackendLogin> | undefined
  const valid =
    login !== undefined &&
    typeof login.token === 'string' &&
    TOKEN_SHAPE.test(login.token) &&
    typeof login.csrf === 'string' &&
    CSRF_SHAPE.test(login.csrf) &&
    (login.scope === 'FULL' || login.scope === 'LIMITED') &&
    typeof login.expiresAt === 'string'
  if (!valid) return fail(event, 503, 'UNAVAILABLE')
  const maxAge = cookieLifetime(login.expiresAt, Date.now())
  setCookie(event, SESSION_COOKIE, login.token!, cookieOptions(maxAge))
  setCookie(event, CSRF_COOKIE, login.csrf!, cookieOptions(maxAge))
  return respond(event, 200, { csrf: login.csrf, expiresAt: login.expiresAt, scope: login.scope })
}

/** The player copy of the protocol: only text and JSON are relayed, always as an attachment. */
async function relayFile(event: H3Event, upstream: Response) {
  const type = upstream.headers.get('content-type') ?? ''
  if (!FORWARDED_TYPES.test(type)) return fail(event, 503, 'UNAVAILABLE')
  const text = await upstream.text()
  const extension = type.toLowerCase().startsWith('application/json') ? 'json' : 'txt'
  setResponseHeader(event, 'Cache-Control', 'no-store')
  setResponseHeader(event, 'Content-Type', type)
  setResponseHeader(event, 'Content-Disposition', `attachment; filename="appeal-protocol.${extension}"`)
  setResponseStatus(event, 200)
  return send(event, text)
}
