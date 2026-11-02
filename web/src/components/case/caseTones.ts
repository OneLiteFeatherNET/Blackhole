// The tone of each case pill, so one colour means one thing across the case list, the player groups and the case bar.
// A tone is a role pair from the MD3 tokens (StatusPill), never a colour of its own:
//  - primary, tertiary, secondary: the open, in-review and waiting work of a case;
//  - error: an active punishment, and an urgent priority;
//  - neutral: a quiet state (closed, expired, low priority);
//  - outline: a quieter state that is still a state (lifted, normal priority).
import type { CasePriority, CaseStatus } from '../../api/types'

export type PillTone = 'neutral' | 'outline' | 'error' | 'primary' | 'secondary' | 'tertiary'

export type PunishmentState = 'ACTIVE' | 'EXPIRED' | 'LIFTED'

export const CASE_STATUS_TONE: Record<CaseStatus, PillTone> = {
  OPEN: 'primary',
  IN_REVIEW: 'tertiary',
  WAITING: 'secondary',
  CLOSED: 'neutral',
}

export const PUNISHMENT_STATE_TONE: Record<PunishmentState, PillTone> = {
  ACTIVE: 'error',
  EXPIRED: 'neutral',
  LIFTED: 'outline',
}

// The API types the state as a string, so an unknown state falls back to neutral rather than to no pill.
export function punishmentStateTone(state: string): PillTone {
  return PUNISHMENT_STATE_TONE[state as PunishmentState] ?? 'neutral'
}

export const PRIORITY_TONE: Record<CasePriority, PillTone> = {
  LOW: 'neutral',
  NORMAL: 'outline',
  HIGH: 'tertiary',
  URGENT: 'error',
}
