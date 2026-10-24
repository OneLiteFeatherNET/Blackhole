import type { components } from './schema'

type Schemas = components['schemas']

export type EnforcementKind = Schemas['EnforcementKind']
export type ServerTargetKind = Schemas['ServerTargetKind']
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
export type EvasionSightings = Schemas['EvasionSightingsResponse']
export type EvasionLinkedOwner = Schemas['EvasionLinkedOwner']
export type EloAdjustResult = Schemas['EloAdjustResponse']
export type ApplyResult = Schemas['PunishmentApplyResponse']
export type RevokeResult = Schemas['PunishmentRevokeResponse']
export type Template = Schemas['PunishTemplateResponse']
export type TemplateStep = Schemas['PunishTemplateStep']
export type Section = Schemas['SectionResponse']
/** The CloudNet tasks, groups and services the proxies report, merged; `available` is false when none is known. */
export type CloudNetCatalogue = Schemas['CatalogueResponse']
export type Report = Schemas['ReportDTO']
export type Appeal = Schemas['AppealDTO']
export type CurrentCaller = Schemas['AuthMeResponse']
export type Evidence = Schemas['EvidenceResponse']
export type EvidencePage = Schemas['EvidencePage']
export type StaffLinkCode = Schemas['StaffLinkCodeResponse']
export type StaffLinkStatus = Schemas['StaffLinkStatusResponse']
export type StaffLink = Schemas['StaffLinkItem']
export type DiscordLinkCode = Schemas['DiscordLinkCodeResponse']
export type DiscordLinkStatus = Schemas['DiscordLinkStatusResponse']
export type DiscordLink = Schemas['DiscordLinkItem']
/** The summary of a vanilla ban-list import or preview. The endpoint is hidden from the published spec, so this is typed by hand. */
export interface VanillaImportResult {
  dryRun: boolean
  playersTotal: number
  playersImported: number
  playersSkippedExisting: number
  playersInvalid: number
  invalidEntries: string[]
  ipsTotal: number
  ipsSkipped: number
  /** The imported entries by original reason, each with the template it is (or would be) linked to. */
  reasonGroups: Array<{ reason: string; templateKey: string; count: number }>
}

/** What the import form submits; the players list is the file's text, pasted or read. */
export interface VanillaImportRequest {
  players: Blob
  ips?: Blob | null
  /** The template every ban is linked to, unless `reasonTemplates` maps its reason to another one. */
  templateKey: string
  /** Original reason text to template key, for a file with different reasons. */
  reasonTemplates?: Record<string, string>
  /** Only report what would happen. */
  dryRun: boolean
}

export type ImportedPunishmentGroups = Schemas['ImportedPunishmentGroups']
export type ImportedPunishmentGroup = Schemas['ImportedPunishmentGroup']
export type ImportedPunishmentRetemplateResult = Schemas['ImportedPunishmentRetemplateResponse']

/** A known staff principal (a person or an application) with the name kept for it. */
export type StaffActor = Schemas['StaffActorItem']

export type ActivePunishment = Schemas['ActivePunishmentItem']
export type ActivePunishmentPage = Schemas['ActivePunishmentPage']
export type Case = Schemas['CaseResponse']
export type CaseListItem = Schemas['CaseListItem']
export type CaseListPage = Schemas['CaseListPage']
export type CaseRelated = Schemas['CaseRelated']
export type CaseStatus = Schemas['CaseStatus']
export type CasePriority = Schemas['CasePriority']
export type CaseTimelineItem = Schemas['CaseTimelineItem']
export type CaseTimelinePage = Schemas['CaseTimelinePage']
export type CaseUpdate = Omit<Schemas['CaseUpdateRequest'], 'clearAssignee' | 'assigneeOid'> & {
  /** The staff member to assign, or null to remove the assignee; absent leaves it alone. */
  assigneeOid?: string | null
}
export type NoteRedactionReason = Schemas['NoteRedactionReason']
export type ActorTrust = Schemas['ActorTrust']
export type CaseActorInfo = Schemas['CaseActorInfo']
export type BanIdLeakReason = Schemas['BanIdLeakReason']
export type BanIdReissueReason = Schemas['BanIdReissueReason']
/** What a message post, edit or delete answers: the message id stays the same across edits. */
export type CaseMessageResult = Schemas['CaseMessageResponse']
export type CaseMessageRedactResult = Schemas['CaseMessageRedactResponse']
export type CaseTranscriptEraseResult = Schemas['CaseTranscriptEraseResponse']
export type CaseBanIdLeakResult = Schemas['CaseBanIdLeakResponse']
export type CaseBanIdReissueResult = Schemas['CaseBanIdReissueResponse']
export type TranscriptFormat = 'json' | 'text'
/** A transcript file as the server sent it; the caller turns it into a download. */
export interface TranscriptFile {
  format: TranscriptFormat
  content: string
  mediaType: string
}

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
  /** How `servers` is read; only meaningful for SERVER sections, everything else saves as PATTERN. */
  targetKind: ServerTargetKind
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
  /** A message to the player, recorded on the case where the player can read it; the note stays internal. */
  playerMessage?: string
}

/** What the timeline asks for; `types` are exact types or groups such as `note` and `appeal`. */
export interface CaseTimelineQuery {
  cursor?: string
  size?: number
  types?: string[]
  actorTrust?: ActorTrust
  from?: number
  to?: number
  order?: 'asc' | 'desc'
}

/** The filters of the case list. */
export interface CaseListQuery {
  status?: CaseStatus
  assignee?: string
  tag?: string
  priority?: CasePriority
  page?: number
  size?: number
}

/** The filters of the overview of punished players; only the ones that are set are sent. */
export interface ActivePunishmentQuery {
  kind?: EnforcementKind
  section?: string
  targetKind?: ServerTargetKind
  permanent?: boolean
  /** Temporary punishments ending before this time (epoch millis). */
  expiresBefore?: number
  automatic?: boolean
  /** The staff member's oid. */
  actor?: string
  sort?: Schemas['ActivePunishmentSort']
  direction?: Schemas['SortDirection']
  page?: number
  size?: number
}
