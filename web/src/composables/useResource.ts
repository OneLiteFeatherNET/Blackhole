import { onMounted, ref, shallowRef, type Ref } from 'vue'
import type { ApiError, ApiResult } from '../api/types'

export interface Resource<T> {
  data: Ref<T | null>
  error: Ref<ApiError | null>
  loading: Ref<boolean>
  reload: () => Promise<void>
}

/**
 * Loads data when the component mounts and again on {@link Resource.reload}. A reload keeps the
 * previous data on screen until the new data arrives, so lists do not flash empty after an action.
 */
export function useResource<T>(fetcher: () => Promise<ApiResult<T>>, options: { immediate?: boolean } = {}): Resource<T> {
  const data = shallowRef<T | null>(null) as Ref<T | null>
  const error = ref<ApiError | null>(null)
  const loading = ref(true)

  async function reload(): Promise<void> {
    loading.value = true
    const result = await fetcher()
    if (result.ok) {
      data.value = result.data
      error.value = null
    } else {
      error.value = result.error
    }
    loading.value = false
  }

  // A resource that is not needed yet (the flat list while another view is shown) waits for its first reload.
  if (options.immediate !== false) onMounted(reload)
  return { data, error, loading, reload }
}
