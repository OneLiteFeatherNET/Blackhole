import type { GuardLevel } from '../../api/types'

/** The levels in the order a selector offers them, least strict first. */
export const GUARD_LEVELS: readonly GuardLevel[] = ['OFF', 'STRICT', 'LOCKDOWN']

/** Where the level in force comes from: the manual setting, the unattended evaluator, or nothing at all. */
export type GuardSource = 'manual' | 'unattended' | 'cleared'

/**
 * The effective level is the stricter of the manual and the unattended level. It is attributed to the manual
 * setting when that is the level in force (also when both agree), to the unattended evaluator when only it is
 * stricter, and reported as cleared when the effective level is OFF.
 */
export function guardSource(state: { manualLevel: string; effectiveLevel: string }): GuardSource {
  if (state.effectiveLevel === 'OFF') return 'cleared'
  return state.manualLevel === state.effectiveLevel ? 'manual' : 'unattended'
}
