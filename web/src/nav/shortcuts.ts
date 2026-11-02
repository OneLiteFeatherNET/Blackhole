import { SHORTCUT_TARGETS } from './navigation'

/*
 * Keyboard shortcuts of the panel: `g` and a letter opens a top destination (SHORTCUT_TARGETS), `?` opens the list of
 * shortcuts. The keys are ignored while the viewer types (a text field, a text area, a select or editable content),
 * while a dialog or the modal navigation is open, and when the caller has switched them off (no role). A `g` waits for
 * the next key only: any other key in between cancels it, so there is no timer.
 */

export interface ShortcutOptions {
  navigate: (to: string) => void
  openHelp: () => void
  /** False while the shortcuts must not act (no role, not signed in). */
  enabled: () => boolean
}

/** Whether keys typed on this element belong to the text, not to the panel. */
export function isTextEntry(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  if (target.isContentEditable || ['true', 'plaintext-only', ''].includes(target.getAttribute('contenteditable') ?? 'false')) return true
  return ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)
}

/** A dialog or a modal navigation is open: the keys belong to it. */
function overlayOpen(): boolean {
  return document.querySelector('[role="dialog"], [aria-modal="true"], dialog[open]') !== null
}

/** Returns the keydown listener. */
export function createShortcuts(options: ShortcutOptions): (event: KeyboardEvent) => void {
  let armed = false

  return (event: KeyboardEvent) => {
    const wasArmed = armed
    armed = false

    if (event.ctrlKey || event.metaKey || event.altKey) return
    if (!options.enabled() || isTextEntry(event.target) || overlayOpen()) return

    if (event.key === '?') {
      options.openHelp()
      return
    }
    if (wasArmed) {
      const target = SHORTCUT_TARGETS[event.key]
      if (target) options.navigate(target)
      else if (event.key === 'g') armed = true
      return
    }
    if (event.key === 'g' && !event.shiftKey) armed = true
  }
}
