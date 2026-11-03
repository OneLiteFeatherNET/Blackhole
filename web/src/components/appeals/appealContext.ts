import type { ComputedRef, InjectionKey } from 'vue'
import type { Appeal } from '../../api/types'

/** What the appeal page shares with its decision child route. */
export interface AppealContext {
  /** The appeal as the page last read it; null while it loads or when it does not exist. */
  appeal: ComputedRef<Appeal | null>
  /** Reads the appeal again, e.g. after a decision. */
  reload: () => Promise<void>
  /** Shows a short confirmation on the page. */
  announce: (text: string) => void
}

export const APPEAL_CONTEXT: InjectionKey<AppealContext> = Symbol('appeal')

/** Appeals a moderator may still decide; the others are pending without a decision or already decided. */
export const DECIDABLE_STATUSES = ['ELIGIBLE_PENDING_REVIEW', 'IN_REVIEW'] as const
