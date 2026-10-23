import type { components } from './schema'

type Schemas = components['schemas']

export type EnforcementKind = Schemas['EnforcementKind']
export type ErrorKind = Schemas['ErrorKind']
export type ReportStatus = Schemas['ReportStatus']
export type ReportCategory = Schemas['ReportCategory']
export type AppealStatus = Schemas['AppealStatus']
export type EloReasonCode = Schemas['EloReasonCode']
export type EvidenceKind = Schemas['EvidenceKind']
export type EvidenceStatus = Schemas['EvidenceStatus']

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
export type CurrentCaller = Schemas['AuthMeResponse']
export type Evidence = Schemas['EvidenceResponse']
export type EvidencePage = Schemas['EvidencePage']
export type StaffLinkCode = Schemas['StaffLinkCodeResponse']
export type StaffLinkStatus = Schemas['StaffLinkStatusResponse']
export type StaffLink = Schemas['StaffLinkItem']

/** Why staff delete an evidence item; the backend records it in the audit trail. */
export type EvidenceDeleteReason = 'ERASURE_REQUEST' | 'WRONG_EVIDENCE' | 'OTHER'

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
  kind: ErrorKind | 'FORBIDDEN' | 'NETWORK' | 'UNKNOWN'
}

export type ApiResult<T> = { ok: true; data: T } | { ok: false; error: ApiError }

/** How a report is resolved; a template is only allowed together with ACTIONED. */
export interface ReportResolution {
  status: ReportStatus
  note?: string
  /** Identifier of the template to apply to the reported player. */
  templateId?: string
}

/** A decision on an appeal; `newExpirationAt` (epoch millis) is required for a duration reduction. */
export interface AppealDecision {
  decision: 'GRANTED_FULL_LIFT' | 'GRANTED_DURATION_REDUCTION' | 'DENIED'
  note?: string
  newExpirationAt?: number
}
