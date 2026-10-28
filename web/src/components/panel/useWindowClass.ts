import { getCurrentScope, onScopeDispose, ref, type Ref } from 'vue'

export type WindowClass = 'compact' | 'medium' | 'expanded'

export interface MediaQueryAnswer {
  readonly matches: boolean
  addEventListener(type: 'change', listener: () => void): void
  removeEventListener(type: 'change', listener: () => void): void
}

export interface WindowClassOptions {
  /** Injected for tests; defaults to window.matchMedia. Without it the window reads as compact. */
  matchMedia?: ((query: string) => MediaQueryAnswer) | undefined
}

// Material Design 3 window size classes: compact below 600 px, medium from 600 px, expanded from 840 px.
// Sheets use these values on purpose; the aside of redesign-ux keeps its own 1024 px breakpoint.
const MEDIUM_QUERY = '(min-width: 600px)'
const EXPANDED_QUERY = '(min-width: 840px)'

/** The Material window size class of the window, kept up to date when it is resized. */
export function useWindowClass(options: WindowClassOptions = {}): Ref<WindowClass> {
  const matchMedia = options.matchMedia ?? (typeof window !== 'undefined' ? window.matchMedia?.bind(window) : undefined)
  const windowClass = ref<WindowClass>('compact')
  if (!matchMedia) return windowClass

  const medium = matchMedia(MEDIUM_QUERY)
  const expanded = matchMedia(EXPANDED_QUERY)
  const update = () => {
    windowClass.value = expanded.matches ? 'expanded' : medium.matches ? 'medium' : 'compact'
  }
  update()
  medium.addEventListener('change', update)
  expanded.addEventListener('change', update)
  if (getCurrentScope()) {
    onScopeDispose(() => {
      medium.removeEventListener('change', update)
      expanded.removeEventListener('change', update)
    })
  }
  return windowClass
}
