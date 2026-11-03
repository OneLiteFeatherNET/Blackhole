import type { RouteLocationRaw } from 'vue-router'
import type { PillTone } from '../case/caseTones'

/**
 * One action of an entity header. An action with `to` is a route (a confirmation dialog), so the header navigates
 * to it and emits nothing; any other action is reported with `action` and the page decides what it does.
 */
export interface EntityAction {
  key: string
  label: string
  testid?: string
  to?: RouteLocationRaw
  /** A disabled action is shown with its `reason` as visible text under the header. */
  disabled?: boolean
  reason?: string
  /** Shown in the error colour in the overflow menu (a removal or a kick). */
  destructive?: boolean
}

export interface EntityStateView {
  label: string
  tone: PillTone
  testid?: string
}

export interface EntityOwner {
  label: string
  to?: RouteLocationRaw
  testid?: string
}

export interface EntityAssignee {
  label: string
  unassigned: boolean
}
