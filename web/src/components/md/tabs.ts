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

/** A sub-pixel difference between a strip's scroll position and its size is rounding, not an edge. */
const EDGE_TOLERANCE_PX = 1

/** Whether tabs are hidden beyond the strip's left (`start`) or right (`end`) edge. */
export interface TabEdges {
  start: boolean
  end: boolean
}

export function tabEdges(scrollLeft: number, scrollWidth: number, clientWidth: number): TabEdges {
  return {
    start: scrollLeft > EDGE_TOLERANCE_PX,
    end: scrollLeft + clientWidth < scrollWidth - EDGE_TOLERANCE_PX,
  }
}

/** The scroll position that shows a tab (its start and end, in strip coordinates) fully inside the visible part. */
export function revealScrollLeft(tab: { start: number; end: number }, view: { left: number; width: number }): number {
  if (tab.start < view.left) return tab.start
  if (tab.end > view.left + view.width) return tab.end - view.width
  return view.left
}
