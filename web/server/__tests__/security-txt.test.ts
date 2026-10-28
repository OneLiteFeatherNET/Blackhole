// @vitest-environment node
import { createApp, createRouter, eventHandler, toWebHandler, type EventHandler } from 'h3'
import { describe, expect, it } from 'vitest'
import { parseUiConfig, type UiConfig } from '../lib/config'
import { applySecurityHeaders, createSpaFallbackHandler, securityTxtHandler, sendErrorResponse } from '../lib/handlers'

const INDEX_HTML = '<!doctype html><title>Blackhole</title>'
const NOW = new Date('2026-10-28T12:00:00Z')
const SIGN_IN_ONLY = {
  BLACKHOLE_UI_OIDC_AUTHORITY: 'https://login.example.org/tenant/v2.0',
  BLACKHOLE_UI_OIDC_CLIENT_ID: 'spa-client',
  BLACKHOLE_UI_API_SCOPE: 'api://x/access_as_user',
}
const CONFIG = parseUiConfig(
  {
    ...SIGN_IN_ONLY,
    BLACKHOLE_UI_ORIGIN: 'https://blackhole.onelitefeather.net',
    BLACKHOLE_SECURITY_CONTACT: 'mailto:security@onelitefeather.net',
    BLACKHOLE_SECURITY_EXPIRES: '2027-06-30T12:00:00Z',
  },
  NOW,
)

/**
 * A fresh in-process app per test, routed the way the Nitro routes are: the static disclosure route beside the
 * catch-all single-page fallback. Only the route table is under test, so the backend is never reachable.
 */
function appWith(uiConfig: UiConfig = CONFIG) {
  const router = createRouter()
  router.get('/.well-known/security.txt', securityTxtHandler)
  router.get('/**', createSpaFallbackHandler(async () => INDEX_HTML))
  const app = createApp({ onError: (error, event) => sendErrorResponse(error, event) })
  app.use(
    eventHandler((event) => {
      event.context.uiConfig = uiConfig
      applySecurityHeaders(event)
    }),
  )
  app.use(router as unknown as EventHandler)
  const web = toWebHandler(app)
  return (path: string) => web(new Request(`http://ui.test${path}`))
}

describe('GET /.well-known/security.txt on the panel', () => {
  it('answers with the disclosure file, not the single-page application', async () => {
    const response = await appWith()('/.well-known/security.txt')

    expect(response.status, 'status').toBe(200)
    expect(response.headers.get('content-type'), 'content type').toBe('text/plain; charset=utf-8')
    expect(await response.text(), 'body').toContain('Canonical: https://blackhole.onelitefeather.net/.well-known/security.txt')
  })

  it('answers 404 without serving index.html when no contact is configured', async () => {
    const response = await appWith(parseUiConfig(SIGN_IN_ONLY, NOW))('/.well-known/security.txt')

    expect(response.status, 'status').toBe(404)
    expect(await response.text(), 'body').not.toContain('<title>')
  })

  it('keeps the single-page fallback for other well-known paths', async () => {
    const response = await appWith()('/.well-known/unknown')

    expect(response.status, 'status').toBe(200)
    expect(await response.text(), 'body').toBe(INDEX_HTML)
  })
})
