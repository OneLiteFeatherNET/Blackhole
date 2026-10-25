import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { OidcClient, UserManager, type User } from 'oidc-client-ts'
import {
  SCOPES,
  createAuthService,
  createUserManager,
  safeReturnTo,
  userManagerSettings,
  type OidcConfig,
  type UserManagerLike,
} from './authService'

const CONFIG: OidcConfig = {
  authority: 'https://login.example.org/tenant/v2.0',
  clientId: 'spa-client',
  apiScope: 'api://backend/access_as_user',
}

function user(overrides: Partial<{ access_token: string; expired: boolean; state: unknown }> = {}): User {
  return { access_token: 'access-1', expired: false, state: '/players', ...overrides } as unknown as User
}

/** A manager that records what the service asked of it; every answer is set per test. */
function fakeManager(overrides: Partial<UserManagerLike> = {}) {
  const renewErrorHandlers: Array<(error: Error) => void> = []
  const manager: UserManagerLike = {
    getUser: vi.fn(async () => user()),
    signinRedirect: vi.fn(async () => undefined),
    signinRedirectCallback: vi.fn(async () => user()),
    signinSilent: vi.fn(async () => user({ access_token: 'access-2' })),
    signoutRedirect: vi.fn(async () => undefined),
    events: { addSilentRenewError: (handler) => renewErrorHandlers.push(handler) },
    ...overrides,
  }
  return { manager, renewErrorHandlers }
}

describe('getAccessToken', () => {
  it('returns the token of the signed-in user', async () => {
    const service = createAuthService(fakeManager().manager)

    expect(await service.getAccessToken()).toBe('access-1')
  })

  it('returns null when nobody is signed in, for example after a reload', async () => {
    const service = createAuthService(fakeManager({ getUser: async () => null }).manager)

    expect(await service.getAccessToken()).toBeNull()
  })

  it('returns null for an expired token', async () => {
    const service = createAuthService(fakeManager({ getUser: async () => user({ expired: true }) }).manager)

    expect(await service.getAccessToken()).toBeNull()
  })
})

describe('signIn', () => {
  it('sends the browser to the authority and remembers where to come back to', async () => {
    const { manager } = fakeManager()
    const service = createAuthService(manager)

    await service.signIn('/players/abc?x=1')

    expect(manager.signinRedirect).toHaveBeenCalledWith({ state: '/players/abc?x=1' })
  })

  it('marks the page as redirecting', async () => {
    const service = createAuthService(fakeManager().manager)

    await service.signIn('/')

    expect(service.redirecting.value).toBe(true)
  })

  it('redirects only once while a redirect is under way', async () => {
    const { manager } = fakeManager()
    const service = createAuthService(manager)

    await service.signIn('/')
    await service.signIn('/')

    expect(manager.signinRedirect).toHaveBeenCalledTimes(1)
  })

  it('clears the redirect flag and rethrows when the authority cannot be reached', async () => {
    const failure = new Error('metadata unreachable')
    const service = createAuthService(fakeManager({ signinRedirect: async () => Promise.reject(failure) }).manager)

    await expect(service.signIn('/')).rejects.toBe(failure)
    expect(service.redirecting.value).toBe(false)
  })
})

describe('completeSignIn', () => {
  it('returns the route the user asked for before signing in', async () => {
    const service = createAuthService(fakeManager({ signinRedirectCallback: async () => user({ state: '/reports' }) }).manager)

    expect(await service.completeSignIn('http://ui.test/auth/callback?code=c&state=s')).toBe('/reports')
  })

  it('passes the callback URL on to the library', async () => {
    const { manager } = fakeManager()
    const service = createAuthService(manager)

    await service.completeSignIn('http://ui.test/auth/callback?code=c&state=s')

    expect(manager.signinRedirectCallback).toHaveBeenCalledWith('http://ui.test/auth/callback?code=c&state=s')
  })

  it('lets a rejected callback surface', async () => {
    const failure = new Error('state mismatch')
    const service = createAuthService(fakeManager({ signinRedirectCallback: async () => Promise.reject(failure) }).manager)

    await expect(service.completeSignIn()).rejects.toBe(failure)
  })
})

describe('safeReturnTo', () => {
  it.each(['/', '/players', '/players/abc?x=1#top'])('keeps the in-app route %s', (value) => {
    expect(safeReturnTo(value)).toBe(value)
  })

  it.each([undefined, null, 42, '', 'players', 'https://evil.example', '//evil.example', '/\\evil.example', '/auth/callback', '/auth/callback?code=x'])(
    'falls back to the start page for %j',
    (value) => {
      expect(safeReturnTo(value)).toBe('/')
    },
  )
})

describe('renew', () => {
  it('returns the new access token', async () => {
    const service = createAuthService(fakeManager().manager)

    expect(await service.renew()).toBe('access-2')
  })

  it('shares one request between concurrent callers', async () => {
    const { manager } = fakeManager()
    const service = createAuthService(manager)

    const tokens = await Promise.all([service.renew(), service.renew()])

    expect(tokens).toEqual(['access-2', 'access-2'])
    expect(manager.signinSilent).toHaveBeenCalledTimes(1)
  })

  it('asks again once the previous renewal has settled', async () => {
    const { manager } = fakeManager()
    const service = createAuthService(manager)

    await service.renew()
    await service.renew()

    expect(manager.signinSilent).toHaveBeenCalledTimes(2)
  })

  it('returns null when the authority refuses', async () => {
    const service = createAuthService(fakeManager({ signinSilent: async () => Promise.reject(new Error('invalid_grant')) }).manager)

    expect(await service.renew()).toBeNull()
  })

  it('returns null when there is nothing to renew', async () => {
    const service = createAuthService(fakeManager({ signinSilent: async () => null }).manager)

    expect(await service.renew()).toBeNull()
  })
})

describe('background renewal', () => {
  it('sends the user through sign-in again, back to the current route, when the authority refuses', async () => {
    const { manager, renewErrorHandlers } = fakeManager()
    createAuthService(manager, () => '/appeals')

    renewErrorHandlers[0]!(new Error('invalid_grant'))
    await Promise.resolve()

    expect(manager.signinRedirect).toHaveBeenCalledWith({ state: '/appeals' })
  })
})

describe('signOut', () => {
  it('ends the authority session', async () => {
    const { manager } = fakeManager()
    const service = createAuthService(manager)

    await service.signOut()

    expect(manager.signoutRedirect).toHaveBeenCalledTimes(1)
    expect(service.redirecting.value).toBe(true)
  })

  it('clears the redirect flag and rethrows when the authority cannot be reached', async () => {
    const failure = new Error('offline')
    const service = createAuthService(fakeManager({ signoutRedirect: async () => Promise.reject(failure) }).manager)

    await expect(service.signOut()).rejects.toBe(failure)
    expect(service.redirecting.value).toBe(false)
  })
})

describe('userManagerSettings', () => {
  const settings = () => userManagerSettings(CONFIG, 'http://ui.test', window.sessionStorage)

  it('uses the authorization code flow, which the library secures with PKCE', () => {
    expect(settings().response_type).toBe('code')
  })

  it('asks for the API scope next to the identity and refresh scopes, without profile', () => {
    expect(settings().scope).toBe('openid offline_access api://backend/access_as_user')
  })

  it('returns to the callback route of the same origin', () => {
    expect(settings().redirect_uri).toBe('http://ui.test/auth/callback')
    expect(settings().post_logout_redirect_uri).toBe('http://ui.test/')
  })

  it('renews tokens in the background', () => {
    expect(settings().automaticSilentRenew).toBe(true)
  })
})

describe('where tokens are kept', () => {
  // The real library against an in-process authority: no network, the clock is faked.
  const NOW = Date.UTC(2030, 0, 1, 12, 0, 0)
  const ACCESS_TOKEN = 'secret-access-token'
  const REFRESH_TOKEN = 'secret-refresh-token'
  let manager: UserManager

  function jwt(claims: Record<string, unknown>): string {
    const part = (value: object) => btoa(JSON.stringify(value)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_')
    return `${part({ alg: 'none', typ: 'JWT' })}.${part(claims)}.`
  }

  function allStorageContents(): string {
    const entries = [localStorage, sessionStorage].flatMap((store) => Object.keys(store).map((key) => `${key}=${store.getItem(key)}`))
    return entries.join('\n')
  }

  beforeEach(() => {
    vi.useFakeTimers({ now: NOW })
    localStorage.clear()
    sessionStorage.clear()
    const issuer = CONFIG.authority
    manager = new UserManager({
      ...userManagerSettings(CONFIG, 'http://ui.test', window.sessionStorage),
      metadata: {
        issuer,
        authorization_endpoint: `${issuer}/authorize`,
        token_endpoint: `${issuer}/token`,
        end_session_endpoint: `${issuer}/logout`,
      },
    })
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        Response.json({
          access_token: ACCESS_TOKEN,
          refresh_token: REFRESH_TOKEN,
          token_type: 'Bearer',
          expires_in: 3600,
          scope: 'openid offline_access api://backend/access_as_user',
          id_token: jwt({ iss: issuer, aud: CONFIG.clientId, sub: 'user-1', iat: NOW / 1000, exp: NOW / 1000 + 3600 }),
        }),
      ),
    )
    // jsdom cannot navigate; the redirect itself is not under test.
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
  })

  afterEach(() => {
    manager.stopSilentRenew()
    manager.events.unload()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  const stateKeys = () => Object.keys(sessionStorage).filter((key) => key.startsWith('oidc.'))

  /** Starts the redirect; the library's promise only settles when the page unloads, so it is not awaited. */
  async function startRedirect(): Promise<string> {
    void manager.signinRedirect({ state: '/players' })
    await vi.waitFor(() => expect(stateKeys(), 'the redirect keeps its state in sessionStorage').toHaveLength(1))
    return stateKeys()[0]!.slice('oidc.'.length)
  }

  async function signInWithCallback() {
    const state = await startRedirect()
    return manager.signinRedirectCallback(`http://ui.test/auth/callback?code=the-code&state=${state}`)
  }

  it('keeps the state of the redirect in sessionStorage until the callback', async () => {
    await startRedirect()

    expect(stateKeys(), 'state entries').toHaveLength(1)
    expect(localStorage.length, 'localStorage entries').toBe(0)
  })

  it('removes the state and the PKCE verifier once the callback completes', async () => {
    await signInWithCallback()

    expect(stateKeys(), 'state entries').toEqual([])
  })

  it('writes no token to any web storage', async () => {
    await signInWithCallback()

    const stored = allStorageContents()
    expect(stored, 'storage contents').not.toContain(ACCESS_TOKEN)
    expect(stored, 'storage contents').not.toContain(REFRESH_TOKEN)
    expect(localStorage.length + sessionStorage.length, 'web storage entries').toBe(0)
  })

  it('still knows the user in memory after the callback', async () => {
    await signInWithCallback()

    expect((await manager.getUser())?.access_token).toBe(ACCESS_TOKEN)
  })

  it('returns the route asked for before signing in', async () => {
    const signedIn = await signInWithCallback()

    expect(signedIn.state).toBe('/players')
  })
})

describe('authorization request', () => {
  const metadata = {
    issuer: CONFIG.authority,
    authorization_endpoint: `${CONFIG.authority}/authorize`,
    token_endpoint: `${CONFIG.authority}/token`,
  }

  it('carries a PKCE challenge with method S256', async () => {
    sessionStorage.clear()
    const manager = new OidcClient({ ...userManagerSettings(CONFIG, 'http://ui.test', window.sessionStorage), metadata })

    const request = await manager.createSigninRequest({ state: '/' })
    const url = new URL(request.url)

    expect(url.searchParams.get('code_challenge_method'), 'code_challenge_method').toBe('S256')
    expect(url.searchParams.get('code_challenge'), 'code_challenge').toBeTruthy()
    expect(url.searchParams.get('response_type'), 'response_type').toBe('code')
  })

  it('asks for no profile scope', async () => {
    const manager = new OidcClient({ ...userManagerSettings(CONFIG, 'http://ui.test', window.sessionStorage), metadata })

    const url = new URL((await manager.createSigninRequest({ state: '/' })).url)

    expect(url.searchParams.get('scope'), 'scope').toBe(`openid offline_access ${CONFIG.apiScope}`)
    expect(SCOPES, 'SCOPES').toBe('openid offline_access')
  })
})

describe('createUserManager origin pin', () => {
  it('refuses to start when the page origin differs from the configured panel origin', () => {
    const config = { ...CONFIG, panelOrigin: 'https://panel.example.org' }

    expect(() => createUserManager(config)).toThrow(/panel origin/i)
  })

  it('starts when the page origin equals the configured panel origin', () => {
    const config = { ...CONFIG, panelOrigin: window.location.origin }

    expect(createUserManager(config), 'a manager is returned').toBeInstanceOf(UserManager)
  })
})
