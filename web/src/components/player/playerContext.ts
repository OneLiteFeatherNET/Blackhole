import { inject, type ComputedRef, type InjectionKey, type Ref } from 'vue'
import type { EloSectionScore } from '../../api/types'

/**
 * What the player page shares with its child routes (punish, warn, adjust, kick). The page owns the data and the
 * message; a route only shows its dialog and reports the result back. Nothing here is written to storage.
 */
export interface PlayerContext {
  owner: string
  /** The UUID of the newest session of the player, or null when none is known. */
  playerUuid: Ref<string | null>
  scores: ComputedRef<EloSectionScore[]>
  hasActivePermanent: (section: string) => boolean
  /** Shows the message and reloads the player and the ELO history after a change. */
  changed: (message: string) => Promise<void>
  /** Shows the message after a warning; the warning dialog stays open to show the step. */
  warned: (message: string) => Promise<void>
  /** Reloads the player without a message (a kick is shown by its own dialog). */
  reload: () => Promise<void>
}

export const PLAYER_KEY: InjectionKey<PlayerContext> = Symbol('player-page')

/** The page context of a child route; a route that is not under the player page fails loudly. */
export function requirePlayerContext(route: string): PlayerContext {
  const context = inject(PLAYER_KEY)
  if (!context) throw new Error(`${route} must be rendered by the player page`)
  return context
}
