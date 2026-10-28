import type { Punishment } from '../../api/types'

// The backend stores free-form metadata; these are the keys it writes (see phoca's Expirable).
const EXPIRATION_KEY = 'expiration_date'
const REASON_KEY = 'reason'

function metaValue(punishment: Punishment, key: string): unknown {
  return (punishment.metaData as Record<string, unknown> | undefined)?.[key]
}

/** When a temporary punishment ends (epoch millis); `null` for permanent ones. */
export function expiresAt(punishment: Punishment): number | null {
  const value = metaValue(punishment, EXPIRATION_KEY)
  return typeof value === 'number' ? value : null
}

/** The template's reason for staff punishments, the section's reason for automatic ones. */
export function reasonOf(punishment: Punishment): string {
  if (punishment.template?.reason) return punishment.template.reason
  const value = metaValue(punishment, REASON_KEY)
  return typeof value === 'string' ? value : ''
}

/** "2 / 3" for a punishment that came from an escalation step, `null` otherwise. */
export function stepLabel(punishment: Punishment): string | null {
  if (punishment.stepIndex === null || punishment.stepIndex === undefined) return null
  if (punishment.stepCount === null || punishment.stepCount === undefined) return null
  return `${punishment.stepIndex + 1} / ${punishment.stepCount}`
}

/** A kick is a moment in the history, never a sanction: it has no end and nothing to lift. */
export function isKick(punishment: Punishment): boolean {
  return punishment.kind === 'KICK'
}

/** A warning is a note the player confirms once; it is never in force, so it is not counted as active. */
export function isWarning(punishment: Punishment): boolean {
  return punishment.kind === 'WARN'
}

/** How many punishments of the history are in force at `now`; kicks, warnings, lifted and ended ones are not counted. */
export function countActive(history: Punishment[], now: number): number {
  return history.filter((entry) => {
    if (isKick(entry) || isWarning(entry) || entry.lifted) return false
    const end = expiresAt(entry)
    return entry.permanent || (end !== null && end > now)
  }).length
}

export function isAutomatic(punishment: Punishment): boolean {
  return metaValue(punishment, 'automatic') === true
}
