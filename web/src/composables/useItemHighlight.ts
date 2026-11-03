import { onMounted, onScopeDispose, ref, watch, type Ref } from 'vue'
import { useRoute } from 'vue-router'
import { anchorDomId } from '../components/case/itemAnchor'
import { useReducedMotion } from '../motion/useReducedMotion'

export interface ItemHighlightOptions {
  /** The item the address names (`#event-<id>`), or null. */
  anchor: () => string | null
  /** Changes whenever the items may have changed: a load, a reload. Nothing is timed; the check runs on change. */
  ready: () => unknown
}

export interface ItemHighlight {
  /** The id of the item that is highlighted, or null. The page binds it to the item's `data-highlighted`. */
  highlighted: Ref<string | null>
}

/**
 * Scrolls to the item the address names and highlights it (`restructure-staff-panel`, decision 3). The highlight stays
 * until the reader clicks outside the item or navigates; no timer removes it. Focus moves to the item so that a screen
 * reader announces it, and the scroll is instant when the system asks for reduced motion. An item that is not on the
 * page (yet) is not highlighted; a later `ready` change checks again.
 */
export function useItemHighlight(options: ItemHighlightOptions): ItemHighlight {
  const route = useRoute()
  const reduced = useReducedMotion()
  const highlighted = ref<string | null>(null)
  let stopListening: (() => void) | null = null

  function clear(): void {
    highlighted.value = null
    stopListening?.()
    stopListening = null
  }

  function highlight(anchor: string, element: HTMLElement): void {
    highlighted.value = anchor
    element.scrollIntoView({ block: 'center', behavior: reduced.value ? 'auto' : 'smooth' })
    element.focus({ preventScroll: true })
    const onPointerDown = (event: Event) => {
      if (!(event.target instanceof Node) || !element.contains(event.target)) clear()
    }
    document.addEventListener('pointerdown', onPointerDown)
    stopListening = () => document.removeEventListener('pointerdown', onPointerDown)
  }

  // Navigating to any other address ends the highlight; the same address is checked again by the watch below.
  watch(
    () => route.fullPath,
    () => clear(),
  )

  // The DOM is read after the update (post flush), so an item rendered by this change is found.
  function check(): void {
    const anchor = options.anchor()
    if (anchor === null) {
      if (highlighted.value !== null) clear()
      return
    }
    if (highlighted.value === anchor) return
    const element = document.getElementById(anchorDomId(anchor))
    if (element !== null) highlight(anchor, element)
  }

  watch(() => [options.anchor(), options.ready()] as const, check, { flush: 'post' })
  // Content that is already there when the page mounts is checked once, after the first render.
  onMounted(check)

  onScopeDispose(clear)

  return { highlighted }
}
