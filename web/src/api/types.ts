import type { components } from './schema'

type Schemas = components['schemas']

export type EnforcementKind = Schemas['EnforcementKind']
export type ErrorKind = Schemas['ErrorKind']
export type ReportStatus = Schemas['ReportStatus']
export type ReportCategory = Schemas['ReportCategory']
export type AppealStatus = Schemas['AppealStatus']
export type EloReasonCode = Schemas['EloReasonCode']

export type DashboardSummary = Schemas['DashboardSummaryResponse']
export type ResolvedPlayer = Schemas['PlayerResolveResponse']
export type Punishment = Schemas['PunishEntryDTO']
export type EloSectionScore = Schemas['EloSectionScore']
export type EloProfile = Schemas['EloProfileResponse']
export type EloEvent = Schemas['EloEventResponse']
export type EloAdjustResult = Schemas['EloAdjustResponse']
export type ApplyResult = Schemas['PunishmentApplyResponse']
export type RevokeResult = Schemas['PunishmentRevokeResponse']
export type Template = Schemas['PunishTemplateResponse']
export type TemplateStep = Schemas['PunishTemplateStep']
export type Section = Schemas['SectionResponse']
export type Report = Schemas['ReportDTO']
export type Appeal = Schemas['AppealDTO']

/** The player's punishments as the profile endpoint returns them (empty collections are omitted). */
export interface PunishmentProfile {
  active: Record<string, Punishment>
  history: Punishment[]
}

/** What the template editor submits; the identifier is only known when editing. */
export interface TemplateDraft {
  key: string
  reason: string
  section: string
  escalationGroup: string | null
  escalationWindow: string | null
  steps: TemplateStep[]
}

/** What the section form submits. */
export interface SectionDraft {
  key: string
  displayName: string
  enforcement: EnforcementKind
  servers: string[]
  baseScore: number
  softThreshold: number | null
  softDuration: string | null
  permanentThreshold: number
  pardonScore: number
  permanentReason: string
  decayPerDay: number
  enabled: boolean
}

/** Server-side preview of applying a template; the server owns the step selection. */
export interface ApplyPreview {
  section: string
  enforcement: EnforcementKind
  step: number
  stepCount: number
  duration: string
  eloCost: number
  scoreBefore: number
  scoreAfter: number
  permanentThreshold: number
  escalatesToPermanent: boolean
}

export interface ApiError {
  /** HTTP status, or 0 when the request never reached the server. */
  status: number
  message: string
  kind: ErrorKind | 'NETWORK' | 'UNKNOWN'
}

export type ApiResult<T> = { ok: true; data: T } | { ok: false; error: ApiError }
