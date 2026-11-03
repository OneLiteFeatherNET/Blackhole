import { computed, ref, watch, type WritableComputedRef } from 'vue'
import { useRoute, useRouter, type LocationQuery } from 'vue-router'
import { readListQuery, writeListQuery, type ListParams, type ListValues } from './listQuery'

function sameValues(a: ListValues, b: ListValues): boolean {
  return Object.keys(a).every((key) => a[key] === b[key])
}

/**
 * The state of a list, read from and written to its address (`listQuery.ts`). The values change at once, so the list
 * never shows a filter that the address does not yet hold; the address follows with a replacement, so Back does not
 * step through each change. A change of a filter starts again at the first page, unless `keepPage` names it (a search
 * that runs on the loaded page) or the page changes in the same change. Back, a reload and a shared link read the
 * address back into the values.
 */
export function useListState(params: ListParams, keepPage: readonly string[] = []): WritableComputedRef<ListValues> {
  const route = useRoute()
  const router = useRouter()
  const values = ref<ListValues>(readListQuery(route.query, params))

  watch(
    () => route.query as LocationQuery,
    (query) => {
      const next = readListQuery(query, params)
      if (!sameValues(next, values.value)) values.value = next
    },
  )

  return computed({
    get: () => values.value,
    set: (changes: ListValues) => {
      const next: ListValues = { ...values.value, ...changes }
      if (sameValues(next, values.value)) return
      const filterChanged = Object.keys(params).some((key) => key !== 'page' && !keepPage.includes(key) && next[key] !== values.value[key])
      if ('page' in params && filterChanged && next.page === values.value.page) next.page = ''
      values.value = next
      void router.replace({ query: writeListQuery(route.query, next, params) })
    },
  })
}
