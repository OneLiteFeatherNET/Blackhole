export const MAX_KICK_REASON = 200

const UUID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i
const IPV4 = /\b\d{1,3}(?:\.\d{1,3}){3}\b/
const IPV6 = /(?:[0-9a-f]{1,4}:){2,7}[0-9a-f]{0,4}|::[0-9a-f]{1,4}/i
const BAN_ID = /#[0-9a-f]{8}\b/i

export type KickReasonProblem = 'empty' | 'tooLong' | 'pii'

/** The same refusals the backend makes (1 to 200 characters, no UUID, IP address or Ban ID), so the form says so first. */
export function kickReasonProblem(reason: string): KickReasonProblem | null {
  const text = reason.trim()
  if (text === '') return 'empty'
  if (text.length > MAX_KICK_REASON) return 'tooLong'
  if (UUID.test(text) || IPV4.test(text) || IPV6.test(text) || BAN_ID.test(text)) return 'pii'
  return null
}
