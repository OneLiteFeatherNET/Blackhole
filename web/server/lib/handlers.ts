import {
  createError,
  eventHandler,
  getRequestIP,
  getRequestWebStream,
  removeResponseHeader,
  send,
  sendWebResponse,
  setResponseHeader,
  setResponseHeaders,
  setResponseStatus,
  type H3Event,
} from 'h3'
import { toPublicConfig, type UiConfig } from './config'
import { isIndexDocument, securityHeaders } from './headers'
import { forwardToBackend } from './proxy'

declare module 'h3' {
  interface H3EventContext {
    /** Validated at startup by the config plugin. */
    uiConfig?: UiConfig
  }
}

function uiConfigOf(event: H3Event): UiConfig {
  const config = event.context.uiConfig
  if (!config) throw createError({ statusCode: 500, statusMessage: 'Server configuration is not loaded' })
  return config
}

/**
 * Security headers on every response, no software disclosure, index revalidation. Nitro answers
 * public assets before any middleware runs, so this is called from the `request` hook instead.
 */
export function applySecurityHeaders(event: H3Event): void {
  setResponseHeaders(event, securityHeaders(event.context.uiConfig?.oidc.authority))
  removeResponseHeader(event, 'X-Powered-By')
  removeResponseHeader(event, 'Server')
  if (isIndexDocument(event.path)) setResponseHeader(event, 'Cache-Control', 'no-cache')
}

/** Error responses: plain text, the same security headers, never cached, no internals. */
export function sendErrorResponse(error: { statusCode?: number; statusMessage?: string; unhandled?: boolean }, event: H3Event) {
  const status = error.unhandled ? 500 : error.statusCode || 500
  const message = error.unhandled || status >= 500 ? 'Server Error' : error.statusMessage || 'Error'
  if (error.unhandled) console.error(`[request error] [${event.method}] ${event.path}`, error)
  setResponseHeaders(event, { ...securityHeaders(event.context.uiConfig?.oidc.authority), 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' })
  setResponseStatus(event, status, message)
  return send(event, `${status} ${message}`)
}

/** `GET /config.json`: the runtime settings, serialised with JSON.stringify so nothing can be injected. */
export const configJsonHandler = eventHandler((event) => {
  setResponseHeader(event, 'Content-Type', 'application/json; charset=utf-8')
  setResponseHeader(event, 'Cache-Control', 'no-store')
  return JSON.stringify(toPublicConfig(uiConfigOf(event)))
})

export const healthzHandler = eventHandler((event) => {
  setResponseHeader(event, 'Content-Type', 'text/plain; charset=utf-8')
  return 'ok'
})

/** SPA fallback: every GET/HEAD path nothing else handled gets the bundled index.html. */
export function createSpaFallbackHandler(readIndex: () => Promise<string | null | undefined>) {
  return eventHandler(async (event) => {
    if (event.method !== 'GET' && event.method !== 'HEAD') {
      throw createError({ statusCode: 404, statusMessage: 'Not Found' })
    }
    const index = await readIndex()
    if (!index) throw createError({ statusCode: 404, statusMessage: 'Not Found' })
    setResponseStatus(event, 200)
    setResponseHeader(event, 'Content-Type', 'text/html; charset=utf-8')
    setResponseHeader(event, 'Cache-Control', 'no-cache')
    return event.method === 'HEAD' ? '' : index
  })
}

/** `/api/**` reverse proxy to the backend; bodies are streamed in both directions. */
export function createApiProxyHandler(fetchImpl: typeof fetch = fetch) {
  return eventHandler(async (event) => {
    const { backendUrl } = uiConfigOf(event)
    const hasBody = event.method !== 'GET' && event.method !== 'HEAD'
    const headers = new Headers()
    for (const [name, value] of Object.entries(event.node.req.headers)) {
      if (Array.isArray(value)) value.forEach((v) => headers.append(name, v))
      else if (value !== undefined) headers.set(name, value)
    }
    const response = await forwardToBackend({
      backendUrl,
      method: event.method,
      path: event.path,
      headers,
      body: hasBody ? getRequestWebStream(event) : undefined,
      clientAddress: getRequestIP(event),
      proto: (event.node.req.socket as { encrypted?: boolean } | undefined)?.encrypted ? 'https' : 'http',
      fetch: fetchImpl,
    })
    return sendWebResponse(event, response)
  })
}
