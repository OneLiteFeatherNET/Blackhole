import { computed, ref, type ComputedRef, type Ref } from 'vue'
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

export interface RoutedActionOptions extends RoutedDialogOptions {
  /** The version of the item as it was read, or null while the read has not completed. */
  version: Ref<number | null>
  /** Sends the change. Only `confirm` calls it: opening the child route never does. */
  send: (expectedVersion: number) => Promise<void>
}

export interface RoutedActionState extends RoutedDialogState {
  /** True while the confirmed change is in flight. */
  pending: Ref<boolean>
  /** True when the last confirmed change was refused; the dialog then stays open. */
  failed: Ref<boolean>
  /** Sends the change with the version that was read, then closes the dialog. */
  confirm: () => Promise<void>
}

/**
 * A state change behind a child route (decision 8, rule 4): the route only shows the confirmation. The request is
 * sent by `confirm`, once, with the version read with the item (`expectedVersion`), and never on navigation.
 */
export function useRoutedAction(options: RoutedActionOptions): RoutedActionState {
  const dialog = useRoutedDialog(options)
  const pending = ref(false)
  const failed = ref(false)

  async function confirm(): Promise<void> {
    const version = options.version.value
    if (!dialog.open.value || pending.value || version === null) return
    pending.value = true
    failed.value = false
    try {
      await options.send(version)
    } catch {
      failed.value = true
      return
    } finally {
      pending.value = false
    }
    await dialog.close()
  }

  return { ...dialog, pending, failed, confirm }
}
