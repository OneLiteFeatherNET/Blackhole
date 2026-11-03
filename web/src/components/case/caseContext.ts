import type { InjectionKey, Ref } from 'vue'
import type { Case, CaseStatus } from '../../api/types'

/** A status change the dialog made; the page shows it and offers the undo of a close. */
export interface CaseStatusApplied {
  kase: Case
  /** The status the case had before the change. */
  previous: CaseStatus
  action: 'close' | 'reopen'
}

/** What the case page shares with its child route dialogs: the case as it was last read, and the change result. */
export interface CaseContext {
  kase: Ref<Case | null>
  applied: (change: CaseStatusApplied) => void
}

export const CASE_CONTEXT_KEY: InjectionKey<CaseContext> = Symbol('case context')
