// The address of an imported-punishment group names it by a short hash of its reason, not by the reason itself: a
// reason is free text from the old ban list and may name a player (GDPR principle 1, staff-panel-security).
// FNV-1a, 32 bits: a lookup key within one list, not a secret.
export function groupKey(reason: string): string {
  let hash = 0x811c9dc5
  for (const byte of new TextEncoder().encode(reason)) {
    hash ^= byte
    hash = Math.imul(hash, 0x01000193) >>> 0
  }
  return hash.toString(16).padStart(8, '0')
}
