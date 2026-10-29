// The rules of the IP ban form, as pure functions so the dialog and the tests share them. The backend
// stays authoritative (protected networks, overlaps, the configured moderator cap); these checks only
// stop an obvious mistake before the request is sent, and they name the same limits the API documents.
import { parseDuration, type DurationFailure } from '../../duration'
import type { ApiError, IpBan } from '../../api/types'

/** Reasons are 1 to 200 characters, the same bound the backend enforces. */
export const REASON_MAX = 200

/** The default `blackhole.ipban.moderator-max-duration` (P30D). The server decides; this only mirrors the default. */
export const MODERATOR_MAX_SECONDS = 30 * 86_400

/** The widest ranges the API takes: IPv4 prefixes shorter than /16 and IPv6 prefixes shorter than /32 are refused. */
const WIDEST_PREFIX = { 4: 16, 6: 32 } as const

export type AddressFailure = 'empty' | 'invalid' | 'tooWide'
export type AddressCheck = { ok: true; address: string } | { ok: false; reason: AddressFailure }

const IPV4 = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/

function isIPv4(text: string): boolean {
  const match = IPV4.exec(text)
  return match !== null && match.slice(1).every((octet) => Number(octet) <= 255)
}

/** The 16-bit groups of an IPv6 address; `::` stands for at least one group and a trailing IPv4 address for two. */
function isIPv6(text: string): boolean {
  if (!/^[0-9a-f:.]+$/i.test(text)) return false
  let body = text
  const lastColon = body.lastIndexOf(':')
  const last = body.slice(lastColon + 1)
  if (last.includes('.')) {
    if (!isIPv4(last)) return false
    body = `${body.slice(0, lastColon + 1)}0:0`
  }
  const pieces = body.split('::')
  if (pieces.length > 2) return false
  const groups = pieces.flatMap((piece) => (piece === '' ? [] : piece.split(':')))
  if (!groups.every((group) => /^[0-9a-f]{1,4}$/i.test(group))) return false
  return pieces.length === 2 ? groups.length <= 7 : groups.length === 8
}

/** One IPv4 or IPv6 address, or a CIDR range of one, as the form takes it. The host bits of a range are the server's to clear. */
export function checkAddress(input: string): AddressCheck {
  const text = input.trim()
  if (text === '') return { ok: false, reason: 'empty' }
  const slash = text.indexOf('/')
  const host = slash < 0 ? text : text.slice(0, slash)
  const family = isIPv4(host) ? 4 : isIPv6(host) ? 6 : null
  if (family === null) return { ok: false, reason: 'invalid' }
  if (slash < 0) return { ok: true, address: text }
  const prefixText = text.slice(slash + 1)
  if (!/^\d{1,3}$/.test(prefixText)) return { ok: false, reason: 'invalid' }
  const prefix = Number(prefixText)
  if (prefix > (family === 4 ? 32 : 128)) return { ok: false, reason: 'invalid' }
  if (prefix < WIDEST_PREFIX[family]) return { ok: false, reason: 'tooWide' }
  return { ok: true, address: text }
}

export interface IpBanFormInput {
  address: string
  reason: string
  /** The duration as typed or as the duration field's ISO form. */
  duration: string
  /** Administrators only: a ban that never expires. */
  permanent: boolean
}

export interface IpBanFormRole {
  administrator: boolean
}

export interface IpBanFormErrors {
  address?: AddressFailure
  reason?: 'empty' | 'tooLong'
  duration?: DurationFailure | 'tooLong'
  permanent?: 'notAllowed'
}

/** The field errors of the create form; an empty object means the form may be sent. */
export function validateIpBanForm(input: IpBanFormInput, role: IpBanFormRole): IpBanFormErrors {
  const errors: IpBanFormErrors = {}
  const address = checkAddress(input.address)
  if (!address.ok) errors.address = address.reason

  const reason = input.reason.trim()
  if (reason === '') errors.reason = 'empty'
  else if (reason.length > REASON_MAX) errors.reason = 'tooLong'

  if (input.permanent) {
    if (!role.administrator) errors.permanent = 'notAllowed'
    return errors
  }
  const duration = parseDuration(input.duration)
  if (!duration.ok) errors.duration = duration.reason
  else if (!role.administrator && duration.seconds > MODERATOR_MAX_SECONDS) errors.duration = 'tooLong'
  return errors
}

/** The expiry the request carries (epoch milliseconds), or null for a permanent ban. Call only with a valid duration. */
export function expiresAtFor(input: Pick<IpBanFormInput, 'duration' | 'permanent'>, now: number): number | null {
  if (input.permanent) return null
  const duration = parseDuration(input.duration)
  return duration.ok ? now + duration.seconds * 1000 : null
}

/** A lift needs a reason that says something; blank is not a reason. */
export function validateLiftReason(reason: string): 'empty' | 'tooLong' | null {
  const text = reason.trim()
  if (text === '') return 'empty'
  if (text.length > REASON_MAX) return 'tooLong'
  return null
}

const CODE_KEYS = new Map<string, string>([
  ['IPBAN_PROTECTED', 'protected'],
  ['IPBAN_RANGE_TOO_WIDE', 'tooWide'],
  ['FORBIDDEN_DURATION', 'forbiddenDuration'],
  ['ALREADY_BANNED', 'alreadyBanned'],
])

/**
 * The key of the message the form shows for a refused ban or lift (`ipBans.errors.<key>`). A stable server
 * code wins; a lift answered 409 without one means the ban is no longer active. Null means "show the server's text".
 */
export function ipBanErrorKey(error: ApiError, action: 'create' | 'lift'): string | null {
  const byCode = error.code ? CODE_KEYS.get(error.code) : undefined
  if (byCode) return byCode
  if (action === 'lift' && error.status === 409) return 'notActive'
  return null
}

export type BanState = 'active' | 'lifted' | 'expired'

/** Lifted wins over expired: a ban lifted before its expiry is shown as lifted. */
export function banState(ban: IpBan, now: number): BanState {
  if (ban.liftedAt !== undefined && ban.liftedAt !== null) return 'lifted'
  if (ban.expiresAt !== undefined && ban.expiresAt !== null && ban.expiresAt <= now) return 'expired'
  return 'active'
}
