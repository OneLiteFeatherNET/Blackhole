import { inject, type InjectionKey } from 'vue'

/**
 * What the punished-entry page shares with its revoke route. The page owns the data and the message; the route only
 * shows the confirmation and reports the revoke back. Nothing here is written to storage.
 */
export interface PunishedContext {
  /** Shows the message and reloads the entry after a change. */
  changed: (message: string) => Promise<void>
}

export const PUNISHED_KEY: InjectionKey<PunishedContext> = Symbol('punished-entry')

/** The page context of the revoke route; a route that is not under the punished entry page fails loudly. */
export function requirePunishedContext(route: string): PunishedContext {
  const context = inject(PUNISHED_KEY)
  if (!context) throw new Error(`${route} must be rendered by the punished entry page`)
  return context
}
