import { InMemoryWebStorage, UserManager, WebStorageStateStore, type User, type UserManagerSettings } from 'oidc-client-ts'
import { ref, type Ref } from 'vue'

/** What `/config.json` carries about sign-in; none of it is secret. */
export interface OidcConfig {
  authority: string
  clientId: string
  /** The API scope the access token is requested for, e.g. `api://<api-client-id>/access_as_user`. */
  apiScope: string
  /**
   * The origin the panel is served from, e.g. `https://panel.example.org`. When set, the app refuses
   * to start on any other origin, so a `redirect_uri` is never built from an unexpected host.
   */
  panelOrigin?: string
}

/** The part of oidc-client-ts' `UserManager` the app relies on; tests provide a fake. */
export interface UserManagerLike {
  getUser(): Promise<User | null>
  signinRedirect(args: { state: string }): Promise<void>
  signinRedirectCallback(url?: string): Promise<User>
  signinSilent(): Promise<User | null>
  signoutRedirect(): Promise<void>
  events: { addSilentRenewError(callback: (error: Error) => void): void }
}

export interface AuthService {
  /** True from the moment the browser is being sent to the authority until the page is left. */
  readonly redirecting: Ref<boolean>
  /** The current access token, or null when nobody is signed in or it has expired. */
  getAccessToken(): Promise<string | null>
  /** Gets a new access token without user interaction; null when the authority refuses. Concurrent calls share one request. */
  renew(): Promise<string | null>
  /** Sends the browser to the authority; `returnTo` is the route to show afterwards. */
  signIn(returnTo: string): Promise<void>
  /** Exchanges the code in the callback URL and returns the route to show next. */
  completeSignIn(url?: string): Promise<string>
  /** Clears the in-memory user and ends the authority session. */
  signOut(): Promise<void>
}

export const CALLBACK_PATH = '/auth/callback'
/**
 * `profile` is dropped: the UI reads no profile claims. `offline_access` stays because background renewal
 * runs on the refresh token (see README); without it the library would fall back to a hidden iframe.
 */
export const SCOPES = 'openid offline_access'

/**
 * Tokens live in the in-memory `userStore`; only the short-lived `state` and PKCE verifier of the
 * redirect round trip go to `sessionStorage`, and the library removes them when the callback completes.
 */
export function userManagerSettings(config: OidcConfig, origin: string, stateStorage: Storage): UserManagerSettings {
  return {
    authority: config.authority,
    client_id: config.clientId,
    redirect_uri: `${origin}${CALLBACK_PATH}`,
    post_logout_redirect_uri: `${origin}/`,
    response_type: 'code',
    scope: `${SCOPES} ${config.apiScope}`,
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
    stateStore: new WebStorageStateStore({ store: stateStorage }),
    automaticSilentRenew: true,
    monitorSession: false,
    loadUserInfo: false,
  }
}

/** PKCE with `code_challenge_method=S256` is always sent by oidc-client-ts (hard-coded, not configurable). */
export function createUserManager(config: OidcConfig): UserManager {
  if (config.panelOrigin !== undefined && config.panelOrigin !== window.location.origin) {
    throw new Error(
      `Refusing to start: the panel origin is pinned to ${config.panelOrigin} but the page is served from ${window.location.origin}`,
    )
  }
  return new UserManager(userManagerSettings(config, window.location.origin, window.sessionStorage))
}

/** Only in-app paths are followed after sign-in, so a crafted state cannot send the user elsewhere. */
export function safeReturnTo(value: unknown): string {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//') || value.startsWith('/\\')) return '/'
  return value.startsWith(CALLBACK_PATH) ? '/' : value
}

/**
 * @param currentPath the route to come back to when the authority rejects a background renewal.
 */
export function createAuthService(manager: UserManagerLike, currentPath: () => string = () => '/'): AuthService {
  const redirecting = ref(false)
  let renewal: Promise<string | null> | null = null

  async function getAccessToken(): Promise<string | null> {
    const user = await manager.getUser()
    return user && !user.expired ? user.access_token : null
  }

  async function signIn(returnTo: string): Promise<void> {
    if (redirecting.value) return
    redirecting.value = true
    try {
      await manager.signinRedirect({ state: safeReturnTo(returnTo) })
    } catch (error) {
      redirecting.value = false
      throw error
    }
  }

  function renew(): Promise<string | null> {
    renewal ??= manager
      .signinSilent()
      .then((user) => user?.access_token ?? null)
      .catch(() => null)
      .finally(() => {
        renewal = null
      })
    return renewal
  }

  // The library renews in the background before expiry; when that is refused the user signs in again.
  manager.events.addSilentRenewError(() => void signIn(currentPath()).catch(() => undefined))

  return {
    redirecting,
    getAccessToken,
    renew,
    signIn,
    async completeSignIn(url) {
      const user = await manager.signinRedirectCallback(url)
      return safeReturnTo(user.state)
    },
    async signOut() {
      redirecting.value = true
      try {
        await manager.signoutRedirect()
      } catch (error) {
        redirecting.value = false
        throw error
      }
    },
  }
}
