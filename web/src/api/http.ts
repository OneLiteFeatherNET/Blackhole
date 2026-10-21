import createClient from 'openapi-fetch'
import type { paths } from './schema'
import type { ApiError, ApiResult, ErrorKind } from './types'

/** The API version every call asks for, see the backend's @Version(ApiVersion.V1). */
export const API_VERSION = '1'
export const API_VERSION_HEADER = 'X-API-VERSION'

export interface HttpOptions {
  /** Where the API is mounted; the reverse proxy strips this prefix. */
  baseUrl: string
  fetch?: typeof fetch
}

export function createHttp(options: HttpOptions) {
  return createClient<paths>({
    baseUrl: options.baseUrl,
    // Looked up on every call so tests (and the browser) can swap the global implementation.
    fetch: options.fetch ?? ((request) => globalThis.fetch(request)),
    headers: { [API_VERSION_HEADER]: API_VERSION },
  })
}

export type Http = ReturnType<typeof createHttp>

interface FetchOutcome<T> {
  data?: T
  error?: unknown
  response: Response
}

const KINDS: readonly string[] = ['VALIDATION', 'NOT_FOUND', 'CONFLICT']

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
    if (typeof body.kind === 'string' && KINDS.includes(body.kind)) kind = body.kind as ErrorKind
  }
  if (kind === 'UNKNOWN') {
    if (status === 404) kind = 'NOT_FOUND'
    else if (status === 409) kind = 'CONFLICT'
    else if (status === 400) kind = 'VALIDATION'
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
