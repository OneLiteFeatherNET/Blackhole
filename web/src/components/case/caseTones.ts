// The tone of each case pill, so one colour means one thing across the case list, the player groups and the case bar.
// A tone is a role pair from the MD3 tokens (StatusPill), never a colour of its own:
//  - primary, tertiary: the open and in-review work of a case;
//  - warning: a case that waits on someone, and a high priority;
//  - success: a lifted punishment;
//  - error: an active punishment, and an urgent priority (the only non-error states that use error are these);
//  - neutral: a quiet state (closed, expired, low priority);
//  - outline: a quieter state that is still a state (normal priority).
import type { CasePriority, CaseStatus } from '../../api/types'

export type PillTone = 'neutral' | 'outline' | 'error' | 'primary' | 'secondary' | 'tertiary' | 'success' | 'warning'

export type PunishmentState = 'ACTIVE' | 'EXPIRED' | 'LIFTED'

export const CASE_STATUS_TONE: Record<CaseStatus, PillTone> = {
  OPEN: 'primary',
  IN_REVIEW: 'tertiary',
  WAITING: 'warning',
  CLOSED: 'neutral',
}

export const PUNISHMENT_STATE_TONE: Record<PunishmentState, PillTone> = {
  ACTIVE: 'error',
  EXPIRED: 'neutral',
  LIFTED: 'success',
}

// The API types the state as a string, so an unknown state falls back to neutral rather than to no pill.
export function punishmentStateTone(state: string): PillTone {
  return PUNISHMENT_STATE_TONE[state as PunishmentState] ?? 'neutral'
}

export const PRIORITY_TONE: Record<CasePriority, PillTone> = {
  LOW: 'neutral',
  NORMAL: 'outline',
  HIGH: 'warning',
  URGENT: 'error',
}
