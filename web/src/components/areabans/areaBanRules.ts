import type { AreaBanKind } from '../../api/types'

/** The longest reason the backend keeps for a ban. */
export const REASON_MAX_LENGTH = 200

/** The duration choice that means no end. Only an admin may pick it. */
export const PERMANENT = 'permanent'

const DAY = 24 * 60 * 60

/** The kinds a role may create: a moderator bans ASNs, an admin also countries. */
export function kindChoices(admin: boolean): AreaBanKind[] {
  return admin ? ['ASN', 'COUNTRY'] : ['ASN']
}

/**
 * The duration choices in seconds, or {@link PERMANENT}. A moderator gets at most seven days (the backend refuses more
 * with FORBIDDEN_DURATION); the choices are only a convenience, the server stays the authority.
 */
export function durationChoices(admin: boolean): string[] {
  return admin ? [String(DAY), String(3 * DAY), String(7 * DAY), String(30 * DAY), PERMANENT] : [String(DAY), String(3 * DAY), String(7 * DAY)]
}

/** The seconds a choice means; null for a permanent ban, which the request then leaves out. */
export function durationSecondsOf(choice: string): number | null {
  return choice === PERMANENT ? null : Number(choice)
}

/** An ASN with or without the "AS" prefix, or a two letter ISO 3166 country code. */
export function valueIsValid(kind: AreaBanKind, value: string): boolean {
  const trimmed = value.trim()
  return kind === 'ASN' ? /^(AS)?\d{1,10}$/i.test(trimmed) : /^[A-Za-z]{2}$/.test(trimmed)
}

/** A ban reason is 1 to 200 characters after trimming. */
export function reasonIsValid(reason: string): boolean {
  const length = reason.trim().length
  return length >= 1 && length <= REASON_MAX_LENGTH
}
