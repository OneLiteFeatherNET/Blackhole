import {
  createError,
  eventHandler,
  removeResponseHeader,
  send,
  setResponseHeader,
  setResponseHeaders,
  setResponseStatus,
  type H3Event,
} from 'h3'
import type { PortalConfig } from './config'
import { isIndexDocument, securityHeaders } from './headers'

declare module 'h3' {
  interface H3EventContext {
    /** Validated at startup by the config plugin. */
    portalConfig?: PortalConfig
  }
}

export function portalConfigOf(event: H3Event): PortalConfig {
  const config = event.context.portalConfig
  if (!config) throw createError({ statusCode: 500, statusMessage: 'Server configuration is not loaded' })
  return config
}

/**
 * Security headers on every response, no software disclosure, index revalidation. Nitro answers
 * public assets before any middleware runs, so this is called from the `request` hook instead.
 */
export function applySecurityHeaders(event: H3Event): void {
  setResponseHeaders(event, securityHeaders())
  removeResponseHeader(event, 'X-Powered-By')
  removeResponseHeader(event, 'Server')
  if (isIndexDocument(event.path)) setResponseHeader(event, 'Cache-Control', 'no-cache')
}

/**
 * Error responses: plain text, the same security headers, never cached, no internals. An
 * unexpected error is logged by method and path only, never by its content or the request body.
 */
export function sendErrorResponse(error: { statusCode?: number; statusMessage?: string; unhandled?: boolean; name?: string }, event: H3Event) {
  const status = error.unhandled ? 500 : error.statusCode || 500
  const message = error.unhandled || status >= 500 ? 'Server Error' : error.statusMessage || 'Error'
  if (error.unhandled) console.error(`[request error] [${event.method}] ${event.path.split('?')[0]} ${error.name ?? 'Error'}`)
  setResponseHeaders(event, { ...securityHeaders(), 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' })
  setResponseStatus(event, status, message)
  return send(event, `${status} ${message}`)
}

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
