import { computed, watch, type ComputedRef } from 'vue'
import { useRoute, useRouter, type LocationQuery } from 'vue-router'

/** The value of a query key when it is one of the allowed values, otherwise the fallback. */
export function choiceOf<T extends string>(query: LocationQuery, key: string, allowed: readonly T[], fallback: T): T {
  const value = query[key]
  return typeof value === 'string' && (allowed as readonly string[]).includes(value) ? (value as T) : fallback
}

/**
 * A view state held in the query (`restructure-staff-panel`, staff-deep-links): the current choice of `key`, or the
 * fallback when the address has no value or an unknown one. An unknown value is removed from the address with a
 * replacement, so Back does not step back to it.
 */
export function useQueryChoice<T extends string>(key: string, allowed: readonly T[], fallback: T): ComputedRef<T> {
  const route = useRoute()
  const router = useRouter()

  watch(
    () => route.query[key],
    (raw) => {
      if (raw === undefined || choiceOf(route.query, key, allowed, fallback) === raw) return
      const rest = { ...route.query }
      delete rest[key]
      void router.replace({ query: rest })
    },
    { immediate: true },
  )

  return computed(() => choiceOf(route.query, key, allowed, fallback))
}
