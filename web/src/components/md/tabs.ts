// The ids that tie a tab to its panel (WAI-ARIA tabs). The owner renders the panels with the same ids.
export const tabDomId = (prefix: string, id: string): string => `${prefix}-tab-${id}`
export const panelDomId = (prefix: string, id: string): string => `${prefix}-panel-${id}`

/** The index a key moves the selection to, or null when the key is not a tab key. Arrow keys wrap around. */
export function tabIndexAfterKey(key: string, current: number, count: number): number | null {
  if (count === 0) return null
  switch (key) {
    case 'ArrowRight':
      return (current + 1) % count
    case 'ArrowLeft':
      return (current - 1 + count) % count
    case 'Home':
      return 0
    case 'End':
      return count - 1
    default:
      return null
  }
}
