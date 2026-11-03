import { computed, type ComputedRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'

export interface RoutedDialogOptions {
  /** The page the dialog opens over, e.g. `report`. */
  parent: string
  /** The child route that is the dialog, e.g. `report-resolve`. */
  child: string
}

export interface RoutedDialogState {
  /** True while the child route is the active route. The route is the only source of this state. */
  open: ComputedRef<boolean>
  /**
   * Closes the dialog by leaving the child route. When the parent page was the previous entry, this is a Back
   * (no new history entry); otherwise the child is replaced by the parent with the same query and fragment.
   */
  close: () => Promise<void>
}

/**
 * The open state of a dialog that is a child route (decision 3 of `restructure-staff-panel`): Back closes it,
 * a reload reopens it, and the address can be shared. Call it in the setup of the child route's component.
 */
export function useRoutedDialog(options: RoutedDialogOptions): RoutedDialogState {
  const route = useRoute()
  const router = useRouter()

  const open = computed(() => route.name === options.child)

  async function close(): Promise<void> {
    const parent = router.resolve({ name: options.parent, params: route.params, query: route.query, hash: route.hash })
    if (router.options.history.state.back === parent.fullPath) {
      router.back()
      return
    }
    await router.replace(parent)
  }

  return { open, close }
}
