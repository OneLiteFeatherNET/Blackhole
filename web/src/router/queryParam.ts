import { computed, type WritableComputedRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'

/**
 * A list's filter in the URL (`restructure-staff-panel`, decision 3): reads one query parameter, accepts only the
 * allowed values and falls back to `fallback` for anything else. Writing replaces the entry, so Back does not
 * step through every filter change. The fallback is not written, which keeps the default address short.
 */
export function useQueryParam<T extends string>(name: string, allowed: readonly T[], fallback: T): WritableComputedRef<T> {
  const route = useRoute()
  const router = useRouter()
  return computed({
    get: () => {
      const raw = route.query[name]
      return allowed.find((value) => value === raw) ?? fallback
    },
    set: (value: T) => {
      void router.replace({ query: { ...route.query, [name]: value === fallback ? undefined : value } })
    },
  })
}

/** A page number in the URL: a whole number from zero, anything else is page zero. */
export function useQueryPage(name = 'page'): WritableComputedRef<number> {
  const route = useRoute()
  const router = useRouter()
  return computed({
    get: () => {
      const raw = Array.isArray(route.query[name]) ? route.query[name][0] : route.query[name]
      const page = Number(raw)
      return Number.isInteger(page) && page > 0 ? page : 0
    },
    set: (page: number) => {
      void router.replace({ query: { ...route.query, [name]: page === 0 ? undefined : String(page) } })
    },
  })
}
