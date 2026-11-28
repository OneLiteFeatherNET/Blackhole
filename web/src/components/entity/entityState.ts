// One mapping from an entity state to its text pill (`restructure-staff-panel`, decision 4). The pill always shows
// the state as text (the key below `entity.state`); the tone is an MD3 role pair and never the only signal.
import type { CaseStatus, ReportStatus } from '../../api/types'
import { CASE_STATUS_TONE, type PillTone } from '../case/caseTones'

export interface EntityStatePill {
  /** The translation key of the state's text, e.g. `entity.state.case.OPEN`. */
  key: string
  tone: PillTone
}

export function caseStatePill(status: CaseStatus): EntityStatePill {
  return { key: `entity.state.case.${status}`, tone: CASE_STATUS_TONE[status] }
}

export type AppealState =
  | 'SUBMITTED'
  | 'ELIGIBLE_PENDING_REVIEW'
  | 'IN_REVIEW'
  | 'GRANTED_FULL_LIFT'
  | 'GRANTED_DURATION_REDUCTION'
  | 'DENIED'
  | 'INELIGIBLE'

const APPEAL_TONE: Record<AppealState, PillTone> = {
  SUBMITTED: 'tertiary',
  ELIGIBLE_PENDING_REVIEW: 'tertiary',
  IN_REVIEW: 'tertiary',
  GRANTED_FULL_LIFT: 'success',
  GRANTED_DURATION_REDUCTION: 'success',
  DENIED: 'neutral',
  INELIGIBLE: 'neutral',
}

/** Pending appeals are the work of the moderators; a decided one is quiet, a granted one is a success. */
export function appealStatePill(status: AppealState): EntityStatePill {
  return { key: `entity.state.appeal.${status}`, tone: APPEAL_TONE[status] }
}

/** A report without a status is treated as open, as the report page always has. */
export function reportStatePill(status: ReportStatus | null): EntityStatePill {
  const state = status ?? 'OPEN'
  const open = state === 'OPEN' || state === 'UNDER_REVIEW'
  return { key: `entity.state.report.${state}`, tone: open ? 'tertiary' : 'neutral' }
}

/** A player is punished while any punishment is in force; the count is the active punishments of the profile. */
export function playerStatePill(activeCount: number): EntityStatePill {
  return activeCount > 0 ? { key: 'entity.state.player.ACTIVE', tone: 'error' } : { key: 'entity.state.player.NONE', tone: 'neutral' }
}
