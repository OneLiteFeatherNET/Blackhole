import { onScopeDispose, ref, type Ref } from 'vue'

export const REDUCED_MOTION_QUERY = '(prefers-reduced-motion: reduce)'

/** Follows the "reduce motion" setting of the system; false where matchMedia does not exist. */
export function useReducedMotion(): Ref<boolean> {
  const reduced = ref(false)
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return reduced

  const query = window.matchMedia(REDUCED_MOTION_QUERY)
  reduced.value = query.matches
  const update = () => (reduced.value = query.matches)
  query.addEventListener('change', update)
  onScopeDispose(() => query.removeEventListener('change', update))
  return reduced
}
