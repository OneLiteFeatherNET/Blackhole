import { securityHeaders } from './headers'

export const DEFAULT_TIMEOUT_MS = 30_000
export const EVIDENCE_UPLOAD_TIMEOUT_MS = 120_000

/** Raised for a request path that could change where the request goes. */
export class HostilePathError extends Error {}

/**
 * Builds the backend URL for a request path like `/api/player/resolve/Steve?x=1`: the `/api`
 * prefix is removed and the result always has the origin of `backendUrl`.
 */
export function buildBackendUrl(backendUrl: string, requestPath: string): URL {
  const queryStart = requestPath.indexOf('?')
  const pathname = queryStart === -1 ? requestPath : requestPath.slice(0, queryStart)
  const search = queryStart === -1 ? '' : requestPath.slice(queryStart)
  if (pathname !== '/api' && !pathname.startsWith('/api/')) throw new HostilePathError('not an /api path')
  const rest = pathname.slice('/api'.length)

  const lower = rest.toLowerCase()
  const hostile =
    rest.includes('\\') ||
    // eslint-disable-next-line no-control-regex
    /[\u0000-\u001f\u007f]/.test(rest) ||
    lower.includes('%5c') ||
    lower.includes('%2f') ||
    lower.includes('%00') ||
    rest.includes('//') ||
    rest.split('/').some((segment) => {
      const decoded = segment.replace(/%2e/gi, '.')
      return decoded === '..' || decoded === '.'
    })
  if (hostile) throw new HostilePathError('hostile path')

  const target = new URL(`${backendUrl}${rest}${search}`)
  if (target.origin !== new URL(backendUrl).origin) throw new HostilePathError('origin changed')
  return target
}

/** Evidence uploads (videos) get 120 s like nginx did; everything else 30 s. */
export function selectTimeoutMs(method: string, backendPathname: string): number {
  const isUpload = method.toUpperCase() === 'POST' && backendPathname.replace(/\/+$/, '').endsWith('/evidence/file')
  return isUpload ? EVIDENCE_UPLOAD_TIMEOUT_MS : DEFAULT_TIMEOUT_MS
}

/** nginx: `client_max_body_size 128m` on /api/evidence/file, its default 1m everywhere else. */
export const EVIDENCE_UPLOAD_MAX_BYTES = 128 * 1024 * 1024
export const DEFAULT_MAX_BODY_BYTES = 1024 * 1024

export function selectMaxBodyBytes(backendPathname: string): number {
  return backendPathname === '/evidence/file' ? EVIDENCE_UPLOAD_MAX_BYTES : DEFAULT_MAX_BODY_BYTES
}

const HOP_BY_HOP = new Set([
  'connection',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'proxy-connection',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
])
// The incoming values of these are replaced, recomputed or unsupported by fetch (expect), never trusted.
const NOT_FORWARDED = new Set(['host', 'expect', 'accept-encoding', 'x-forwarded-for', 'x-forwarded-proto', 'x-forwarded-host', 'forwarded'])

export function buildForwardHeaders(incoming: Headers, clientAddress: string | undefined, proto: string): Headers {
  const headers = new Headers()
  incoming.forEach((value, name) => {
    if (!HOP_BY_HOP.has(name) && !NOT_FORWARDED.has(name)) headers.set(name, value)
  })
  if (clientAddress) headers.set('x-forwarded-for', clientAddress)
  headers.set('x-forwarded-proto', proto)
  return headers
}

const OWN_HEADERS = new Set(Object.keys(securityHeaders()).map((name) => name.toLowerCase()))
const DROPPED_RESPONSE = new Set([
  ...HOP_BY_HOP,
  ...OWN_HEADERS,
  'server',
  'x-powered-by',
  'content-encoding',
  'content-length',
  'cache-control',
  'set-cookie',
])

/** Copies the backend response headers that are safe to relay (fetch has already decoded the body). */
export function buildResponseHeaders(upstream: Headers): Headers {
  const headers = new Headers()
  upstream.forEach((value, name) => {
    if (!DROPPED_RESPONSE.has(name)) headers.set(name, value)
  })
  for (const cookie of upstream.getSetCookie()) headers.append('set-cookie', cookie)
  headers.set('cache-control', 'no-store')
  return headers
}

export interface ProxyRequest {
  backendUrl: string
  method: string
  /** Path and query as received, e.g. `/api/player/resolve/Steve?x=1`. */
  path: string
  headers: Headers
  /** Streamed request body, passed through unread; absent for GET/HEAD. */
  body?: ReadableStream<Uint8Array>
  clientAddress?: string
  proto: string
  fetch: typeof fetch
  /** Defaults to a timeout chosen by {@link selectTimeoutMs}; injectable for tests. */
  signal?: AbortSignal
}

function text(status: number, message: string): Response {
  return new Response(message, { status, headers: { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store' } })
}

/** Forwards one request to the backend and returns its response, or a plain-text 400/502/504. */
export async function forwardToBackend(request: ProxyRequest): Promise<Response> {
  let target: URL
  try {
    target = buildBackendUrl(request.backendUrl, request.path)
  } catch (error) {
    if (error instanceof HostilePathError) return text(400, 'Bad request path')
    throw error
  }
  const maxBytes = selectMaxBodyBytes(target.pathname)
  const declared = Number(request.headers.get('content-length'))
  if (request.headers.get('content-length') !== null && Number.isFinite(declared) && declared > maxBytes) {
    return text(413, 'Request body too large')
  }
  const signal = request.signal ?? AbortSignal.timeout(selectTimeoutMs(request.method, target.pathname))
  let received = 0
  let tooLarge = false
  const init: RequestInit & { duplex?: 'half' } = {
    method: request.method,
    headers: buildForwardHeaders(request.headers, request.clientAddress, request.proto),
    redirect: 'manual',
    signal,
  }
  if (request.body && request.method !== 'GET' && request.method !== 'HEAD') {
    init.body = request.body.pipeThrough(
      new TransformStream<Uint8Array, Uint8Array>({
        transform(chunk, controller) {
          received += chunk.byteLength
          if (received > maxBytes) {
            tooLarge = true
            controller.error(new Error('request body too large'))
          } else {
            controller.enqueue(chunk)
          }
        },
      }),
    )
    init.duplex = 'half'
  }
  try {
    const upstream = await request.fetch(target, init)
    if (tooLarge) return text(413, 'Request body too large')
    return new Response(upstream.body, {
      status: upstream.status,
      statusText: upstream.statusText,
      headers: buildResponseHeaders(upstream.headers),
    })
  } catch (error) {
    if (tooLarge) return text(413, 'Request body too large')
    const name = (error as { name?: string } | null)?.name
    if (name === 'TimeoutError' || name === 'AbortError') return text(504, 'Backend timed out')
    return text(502, 'Backend unreachable')
  }
}
