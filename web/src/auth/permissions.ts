/* What each role may do in the UI; the backend (see the permission matrix) is authoritative. */

/** Whether the roles allow reading staff data at all; `METRICS` alone is for the metrics scraper, not for staff.
 * The backend expands the hierarchy: ADMIN implies MODERATOR implies VIEWER. */
export function canRead(roles: readonly string[]): boolean {
  return roles.some((role) => role === 'ADMIN' || role === 'MODERATOR' || role === 'VIEWER')
}

/** Punishments, adjustments, report and appeal decisions, evidence upload: the lowest role is MODERATOR. */
export function canModerate(roles: readonly string[]): boolean {
  return roles.includes('ADMIN') || roles.includes('MODERATOR')
}

/** Sections, templates and evidence deletion. */
export function canAdminister(roles: readonly string[]): boolean {
  return roles.includes('ADMIN')
}
