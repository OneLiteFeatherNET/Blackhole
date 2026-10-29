import createClient from 'openapi-fetch'
import type { paths } from './schema'
import type { ApiError, ApiResult } from './types'

/** The API version every call asks for, see the backend's @Version(ApiVersion.V1). */
export const API_VERSION = '1'
export const API_VERSION_HEADER = 'X-API-VERSION'

/** What the HTTP layer needs from sign-in; the app wires it to the auth service. */
export interface HttpAuth {
  /** The current access token, null when there is none or it has expired. */
  getAccessToken(): Promise<string | null>
  /** A new access token without user interaction, null when the authority refuses. */
  renew(): Promise<string | null>
  /** Renewal failed or the renewed token was refused too: start sign-in from scratch. */
  signIn(): void
  /** The backend answered 403: tell the user, do not retry. */
  forbidden(): void
}

export interface HttpOptions {
  /** Where the API is mounted; the reverse proxy strips this prefix. */
  baseUrl: string
  auth: HttpAuth
  fetch?: typeof fetch
}

/**
 * Adds the bearer token. On 401 the token is renewed once and the request repeated once; when that
 * does not help, sign-in starts again. A 403 is reported and never retried.
 */
export function authenticatedFetch(auth: HttpAuth, send: typeof fetch): (request: Request) => Promise<Response> {
  const attempt = (request: Request, token: string) => {
    request.headers.set('Authorization', `Bearer ${token}`)
    return send(request)
  }
  const settle = (response: Response) => {
    if (response.status === 403) auth.forbidden()
    return response
  }
  return async (request) => {
    const retry = request.clone()
    const token = await auth.getAccessToken()
    const first = token ? await attempt(request, token) : null
    if (first && first.status !== 401) return settle(first)
    const renewed = await auth.renew()
    if (!renewed) {
      auth.signIn()
      return first ?? new Response(null, { status: 401 })
    }
    const second = await attempt(retry, renewed)
    if (second.status === 401) auth.signIn()
    return settle(second)
  }
}

export function createHttp(options: HttpOptions) {
  // Looked up on every call so tests (and the browser) can swap the global implementation.
  const send = options.fetch ?? ((request: Request) => globalThis.fetch(request))
  return createClient<paths>({
    baseUrl: options.baseUrl,
    fetch: authenticatedFetch(options.auth, send as typeof fetch),
    headers: { [API_VERSION_HEADER]: API_VERSION },
  })
}

export type Http = ReturnType<typeof createHttp>

interface FetchOutcome<T> {
  data?: T
  error?: unknown
  response: Response
}

const KINDS: readonly string[] = ['VALIDATION', 'NOT_FOUND', 'CONFLICT', 'ROLLBACK_SELECTOR_INVALID', 'ROLLBACK_TOO_LARGE']

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

/** Pulls the human readable message out of the backend's error DTOs and Micronaut's own error body. */
export function errorFromBody(status: number, body: unknown): ApiError {
  let message = ''
  let kind: ApiError['kind'] = 'UNKNOWN'
  if (typeof body === 'string') {
    // Some endpoints answer an error with a plain text body.
    message = body.trim()
  } else if (isRecord(body)) {
    const embedded = isRecord(body._embedded) ? body._embedded.errors : undefined
    if (typeof body.errorMessage === 'string') message = body.errorMessage
    else if (Array.isArray(embedded) && isRecord(embedded[0]) && typeof embedded[0].message === 'string') {
      // Micronaut's bean validation lists the violated constraint here; "message" is only the status text.
      message = embedded[0].message
    } else if (typeof body.message === 'string') message = body.message
    if (typeof body.kind === 'string' && KINDS.includes(body.kind)) kind = body.kind as ApiError['kind']
  }
  if (kind === 'UNKNOWN') {
    if (status === 404) kind = 'NOT_FOUND'
    else if (status === 409) kind = 'CONFLICT'
    else if (status === 400) kind = 'VALIDATION'
    else if (status === 401) kind = 'UNAUTHORIZED'
    else if (status === 403) kind = 'FORBIDDEN'
  }
  return { status, message: message || `HTTP ${status}`, kind }
}

/** Turns an openapi-fetch outcome (or a failed request) into an {@link ApiResult}; never throws. */
export async function toResult<T>(request: Promise<FetchOutcome<T>>): Promise<ApiResult<T>> {
  try {
    const { data, error, response } = await request
    if (response.ok && data !== undefined) return { ok: true, data }
    if (response.ok) return { ok: true, data: undefined as T }
    return { ok: false, error: errorFromBody(response.status, error) }
  } catch (cause) {
    const message = cause instanceof Error ? cause.message : String(cause)
    return { ok: false, error: { status: 0, message, kind: 'NETWORK' } }
  }
}

export function map<T, U>(result: ApiResult<T>, transform: (data: T) => U): ApiResult<U> {
  return result.ok ? { ok: true, data: transform(result.data) } : result
}
