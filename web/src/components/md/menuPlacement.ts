// Positions an MD3 menu next to its anchor. Pure so the flip and clamp rules can be unit-tested with
// fixed rectangles instead of a layout engine.

export interface AnchorRect {
  left: number
  top: number
  bottom: number
  width: number
}

export interface MenuPlacement {
  left: number
  width: number
  /** Distance from the viewport top when the menu opens downwards. */
  top?: number
  /** Distance from the viewport bottom when the menu opens upwards. */
  bottom?: number
  maxHeight: number
  up: boolean
}

/** Minimum distance between the menu and the viewport edge. */
export const VIEWPORT_MARGIN = 8
/** Distance between the field and the menu. */
export const ANCHOR_GAP = 4
export const ITEM_HEIGHT = 48
export const MENU_PADDING = 16
/** Five and a half single-line items: the half item cut off at the bottom hints that the menu scrolls. */
export const MAX_MENU_HEIGHT = 5.5 * ITEM_HEIGHT + MENU_PADDING

export function placeMenu(
  anchor: AnchorRect,
  viewport: { width: number; height: number },
  itemCount: number,
): MenuPlacement {
  const below = viewport.height - anchor.bottom - VIEWPORT_MARGIN - ANCHOR_GAP
  const above = anchor.top - VIEWPORT_MARGIN - ANCHOR_GAP
  const wanted = Math.min(MAX_MENU_HEIGHT, itemCount * ITEM_HEIGHT + MENU_PADDING)
  // Open upwards only when the menu would be cut off below and there is more room above.
  const up = below < wanted && above > below
  const width = Math.min(anchor.width, viewport.width - 2 * VIEWPORT_MARGIN)
  const left = Math.min(Math.max(anchor.left, VIEWPORT_MARGIN), viewport.width - VIEWPORT_MARGIN - width)
  return {
    left,
    width,
    up,
    maxHeight: Math.max(0, Math.min(MAX_MENU_HEIGHT, up ? above : below)),
    ...(up ? { bottom: viewport.height - anchor.top + ANCHOR_GAP } : { top: anchor.bottom + ANCHOR_GAP }),
  }
}
