import { timingSafeEqual } from 'node:crypto'
import {
  deleteCookie,
  eventHandler,
  getCookie,
  getHeader,
  getQuery,
  getRequestIP,
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
  IDEMPOTENCY_HEADER,
  MAX_BODY_BYTES,
  ROUTES,
  SESSION_COOKIE,
  type BackendLogin,
  type ErrorBody,
  type ErrorCode,
  type RouteSpec,
} from '../../src/api/contract'
import type { PortalConfig } from './config'

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

function sameSecret(a: string | undefined, b: string | undefined): boolean {
  if (!a || !b) return false
  const left = Buffer.from(a)
  const right = Buffer.from(b)
  return left.length === right.length && timingSafeEqual(left, right)
}

function findRoute(method: string, pathname: string): RouteSpec | undefined {
  return ROUTES.find((route) => route.method === method && route.path === pathname)
}

/** Seconds until `expiresAt`, never beyond the absolute session limit, at least 1. */
function cookieLifetime(expiresAt: unknown, now: number): number {
  const at = typeof expiresAt === 'string' ? Date.parse(expiresAt) : Number.NaN
  if (Number.isNaN(at)) return ABSOLUTE_SESSION_SECONDS
  return Math.min(ABSOLUTE_SESSION_SECONDS, Math.max(1, Math.floor((at - now) / 1000)))
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

async function readBody(event: H3Event, route: RouteSpec): Promise<{ ok: true; json?: string } | { ok: false; status: number; code: ErrorCode }> {
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
  const picked: Record<string, string> = {}
  for (const field of route.body) {
    const value = source[field]
    if (typeof value !== 'string') return { ok: false, status: 400, code: 'BAD_REQUEST' }
    picked[field] = value
  }
  return { ok: true, json: JSON.stringify(picked) }
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
    const route = findRoute(event.method, pathname)
    if (!route) return fail(event, 404, 'NOT_FOUND')

    const changesState = route.method !== 'GET'
    if (changesState && getHeader(event, 'origin') !== config.portalOrigin) return fail(event, 403, 'ORIGIN')

    const token = getCookie(event, SESSION_COOKIE)
    if (route.session && !token) {
      // Logging out without a session is already done.
      if (route.method === 'DELETE') {
        clearSession(event)
        return respond(event, 204)
      }
      clearSession(event)
      return fail(event, 401, 'SESSION_EXPIRED')
    }
    if (route.session && changesState && !sameSecret(getHeader(event, CSRF_HEADER), getCookie(event, CSRF_COOKIE))) {
      return fail(event, 403, 'CSRF')
    }

    const query = backendQuery(event, route)
    if (!query) return fail(event, 400, 'BAD_REQUEST')
    const body = await readBody(event, route)
    if (!body.ok) return fail(event, body.status, body.code)

    const headers = new Headers({ accept: route.path === '/api/appeal/protocol' ? 'text/plain, application/json' : 'application/json' })
    if (body.json !== undefined) headers.set('content-type', 'application/json')
    if (route.session && token) headers.set('authorization', `Bearer ${token}`)
    const client = getRequestIP(event, { xForwardedFor: true })
    if (client) headers.set('x-forwarded-for', client)
    const idempotency = getHeader(event, IDEMPOTENCY_HEADER)
    if (idempotency && IDEMPOTENCY_SHAPE.test(idempotency) && changesState) headers.set(IDEMPOTENCY_HEADER, idempotency)

    const search = query.size > 0 ? `?${query.toString()}` : ''
    let upstream: Response
    try {
      upstream = await fetchImpl(`${config.backendUrl}${route.backend}${search}`, {
        method: route.method,
        headers,
        body: body.json,
        redirect: 'manual',
        signal: AbortSignal.timeout(BACKEND_TIMEOUT_MS),
      })
    } catch {
      if (route.method === 'DELETE') clearSession(event)
      return fail(event, 503, 'UNAVAILABLE')
    }

    if (route.method === 'POST' && !route.session) return finishLogin(event, upstream)
    if (route.method === 'DELETE') {
      clearSession(event)
      return respond(event, 204)
    }
    if (!upstream.ok) {
      const mapped = mapBackendError(upstream.status, await parseJson(upstream))
      if (mapped.status === 401) clearSession(event)
      return respond(event, mapped.status, mapped.error)
    }
    if (route.path === '/api/appeal/protocol') return relayFile(event, upstream)
    if (upstream.status === 204) return respond(event, 204)
    const json = await parseJson(upstream)
    if (json === undefined) return fail(event, 503, 'UNAVAILABLE')
    return respond(event, 200, json)
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
