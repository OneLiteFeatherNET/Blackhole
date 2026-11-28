import { onBeforeUnmount, ref, type Ref } from 'vue'

/** MD3 window size classes: compact below 600dp, medium up to 839dp, expanded from 840dp. */
export type Breakpoint = 'compact' | 'medium' | 'expanded'

const MEDIUM = '(min-width: 600px)'
const EXPANDED = '(min-width: 840px)'

/** The current size class, kept up to date. Without `matchMedia` (old browsers, tests) it is `expanded`. */
export function useBreakpoint(): Ref<Breakpoint> {
  const current = ref<Breakpoint>('expanded')
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return current

  const medium = window.matchMedia(MEDIUM)
  const expanded = window.matchMedia(EXPANDED)
  const update = () => {
    current.value = expanded.matches ? 'expanded' : medium.matches ? 'medium' : 'compact'
  }
  update()
  medium.addEventListener('change', update)
  expanded.addEventListener('change', update)
  onBeforeUnmount(() => {
    medium.removeEventListener('change', update)
    expanded.removeEventListener('change', update)
  })
  return current
}
