import type { Breakpoint } from './useBreakpoint'

/*
 * The list-detail layout of cases and reports (design decision 5). From 840dp the list stays on the left and the detail
 * on the right, the detail area says what to select while nothing is open. Below 840dp an open item replaces the list.
 * The keys of a list: `j` and `k` move the row, `Enter` opens it, `Esc` closes the detail. They never act while the
 * viewer types, while a dialog or a menu is open, or with a modifier key, and `Enter` leaves buttons, links and
 * checkboxes to their own keys.
 */

/** Whether the list is on screen: always beside the detail at expanded width, alone otherwise while nothing is open. */
export function listIsShown(size: Breakpoint, itemOpen: boolean): boolean {
  return size === 'expanded' || !itemOpen
}

/** Whether the detail area is on screen: always at expanded width (with a placeholder), otherwise only for an open item. */
export function detailIsShown(size: Breakpoint, itemOpen: boolean): boolean {
  return size === 'expanded' || itemOpen
}

/** The element id of a row, so the focus can go back to it. */
export function rowDomId(kind: string, key: string): string {
  return `${kind}-${key}`
}

/** Whether keys typed on this element belong to a text the viewer writes. Checkboxes and buttons do not. */
export function isTyping(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  if (target.isContentEditable) return true
  if (target.tagName === 'TEXTAREA' || target.tagName === 'SELECT') return true
  if (target.tagName === 'INPUT') return !['button', 'checkbox', 'radio', 'reset', 'submit'].includes((target as HTMLInputElement).type)
  return false
}

/** An element that answers Enter itself: a link, a button, a form control or a menu item. */
function answersEnter(target: EventTarget | null): boolean {
  return target instanceof Element && target.closest('a, button, input, select, textarea, [role="button"], [role="menuitem"]') !== null
}

/** A dialog or a menu is open: the keys belong to it. */
function overlayOpen(): boolean {
  return document.querySelector('[role="dialog"], [role="menu"], [aria-modal="true"], dialog[open]') !== null
}

export interface ListKeyOptions {
  /** The list is on screen; `j`, `k` and `Enter` act only then. */
  listShown: () => boolean
  /** An item is open; `Esc` closes it only then. */
  detailOpen: () => boolean
  next: () => void
  previous: () => void
  open: () => void
  close: () => void
}

/** Returns the keydown listener of a list. The caller adds it to the document and removes it when the list goes. */
export function createListKeys(options: ListKeyOptions): (event: KeyboardEvent) => void {
  return (event: KeyboardEvent) => {
    if (event.ctrlKey || event.metaKey || event.altKey) return
    if (isTyping(event.target) || overlayOpen()) return

    switch (event.key) {
      case 'j':
        if (!options.listShown()) return
        event.preventDefault()
        options.next()
        return
      case 'k':
        if (!options.listShown()) return
        event.preventDefault()
        options.previous()
        return
      case 'Enter':
        if (!options.listShown() || answersEnter(event.target)) return
        event.preventDefault()
        options.open()
        return
      case 'Escape':
        if (!options.detailOpen()) return
        event.preventDefault()
        options.close()
        return
    }
  }
}
