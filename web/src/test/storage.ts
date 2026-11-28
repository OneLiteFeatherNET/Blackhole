/**
 * vue-router 5.5.1 writes its own scroll bookkeeping to `sessionStorage` under this key on the first navigation,
 * even though the app sets no `scrollBehavior` (5.4.0, which main uses, does not). The value is the router's
 * empty position map `{}`; it never holds app data. Storage guards therefore ignore exactly this key and require
 * its value to stay empty instead of counting it as an app entry.
 */
export const VUE_ROUTER_SCROLL_KEY = 'vue-router:scroll'

/** Keys of the app's own session storage entries, without the router's scroll bookkeeping. */
export function appSessionStorageKeys(): string[] {
  return Object.keys(sessionStorage).filter((key) => key !== VUE_ROUTER_SCROLL_KEY)
}

/** The router's scroll bookkeeping must stay an empty map, so it can never carry app data. */
export function routerScrollBookkeeping(): string {
  return sessionStorage.getItem(VUE_ROUTER_SCROLL_KEY) ?? '{}'
}
