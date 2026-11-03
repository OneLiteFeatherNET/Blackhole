import type { InjectionKey, Ref } from 'vue'
import type { Evidence } from '../../api/types'

/** What the evidence item page shares with its delete route: the item as it was read. */
export interface EvidenceItemContext {
  item: Ref<Evidence | null>
}

export const EVIDENCE_ITEM_KEY: InjectionKey<EvidenceItemContext> = Symbol('evidence item')
