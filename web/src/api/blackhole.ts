import { openCaseEvents, type CaseEvents, type CaseEventsState } from './caseEvents'
import { API_VERSION, API_VERSION_HEADER, authenticatedFetch, createHttp, errorFromBody, map, toResult, type HttpAuth } from './http'
import type { components } from './schema'
import { EVIDENCE_SOURCE_SYSTEM, uploadEvidence, type Upload } from './uploadEvidence'
import type {
  ActivePunishmentPage,
  ActivePunishmentQuery,
  ApiResult,
  Case,
  CaseListPage,
  CaseListQuery,
  CaseTimelinePage,
  CaseTimelineQuery,
  CaseUpdate,
  NoteRedactionReason,
  BanIdLeakReason,
  BanIdReissueReason,
  CaseBanIdLeakResult,
  CaseBanIdReissueResult,
  ChatAttachment,
  EvidenceCounts,
  EvidenceShare,
  EvidenceSharePreview,
  EvidenceSharePreviewRequest,
  EvidenceShareRequest,
  ShareRevokeReason,
  CaseMessageRedactResult,
  CaseMessageResult,
  CaseTimelineItem,
  CaseTranscriptEraseResult,
  CaseParticipantErasure,
  CaseProtocolVersion,
  TranscriptFormat,
  CaseProtocolText,
  TranscriptFile,
  ApplyPreview,
  AuditPage,
  AuditQuery,
  Appeal,
  AppealConditions,
  AppealDecision,
  CloudNetCatalogue,
  ProxyCapabilities,
  ClientRule,
  ClientRuleCatalogue,
  ClientRuleDraft,
  ClientRuleHit,
  CurrentCaller,
  ApplyResult,
  DashboardSummary,
  InboxSummary,
  KickAccepted,
  KickMassRequest,
  KickRequest,
  KickResult,
  EloAdjustResult,
  EvasionSightings,
  EloEvent,
  EloProfile,
  Evidence,
  EvidenceDeleteReason,
  EvidencePage,
  Punishment,
  PunishmentProfile,
  Report,
  ReportResolution,
  ResolvedPlayer,
  RevokeResult,
  Section,
  StaffActor,
  DiscordLink,
  DiscordLinkCode,
  DiscordLinkStatus,
  StaffLink,
  StaffLinkCode,
  StaffLinkStatus,
  SectionDraft,
  Template,
  TemplateDraft,
  VanillaImportRequest,
  VanillaImportResult,
  ImportedPunishmentGroups,
  ImportedPunishmentRetemplateResult,
} from './types'

export interface ApiOptions {
  baseUrl: string
  /** Supplies the bearer token and reacts to 401 and 403, see {@link HttpAuth}. */
  auth: HttpAuth
  fetch?: typeof fetch
}

/** Staff-written notes are limited to 500 characters by the backend. */
export const MAX_NOTE_LENGTH = 500

/**
 * The calls the UI makes, one method per use case. Every method resolves to an {@link ApiResult}
 * and never throws, so screens branch on `ok` instead of wrapping calls in try/catch.
 */
export interface BlackholeApi {
  /** Who the token belongs to and which roles it maps to; an empty role list is a valid answer. */
  me(): Promise<ApiResult<CurrentCaller>>
  dashboard(): Promise<ApiResult<DashboardSummary>>
  /** The four sections of the inbox: counts and the oldest items of each. */
  inbox(): Promise<ApiResult<InboxSummary>>
  /** Resolves a player name; the result carries the hashed `owner` all other calls use. */
  resolvePlayer(name: string): Promise<ApiResult<ResolvedPlayer & { owner: string }>>
  /** A player without any punishment has no profile; that is an empty profile, not an error. */
  profile(owner: string): Promise<ApiResult<PunishmentProfile>>
  eloProfile(owner: string): Promise<ApiResult<EloProfile>>
  /** All events of one section, newest first. */
  eloHistory(owner: string, section: string): Promise<ApiResult<EloEvent[]>>
  templates(): Promise<ApiResult<Template[]>>
  createTemplate(draft: TemplateDraft): Promise<ApiResult<Template>>
  updateTemplate(identifier: string, draft: TemplateDraft): Promise<ApiResult<Template>>
  /** Fails with a CONFLICT while a punishment still references the template. */
  deleteTemplate(identifier: string): Promise<ApiResult<Template>>
  sections(): Promise<ApiResult<Section[]>>
  createSection(draft: SectionDraft): Promise<ApiResult<Section>>
  /** Replaces the section's definition; `draft.enabled` can re-enable a disabled section. */
  updateSection(draft: SectionDraft): Promise<ApiResult<Section>>
  disableSection(key: string): Promise<ApiResult<Section>>
  /** What the proxies report from CloudNet; any signed-in role may read it. Never required to save a section. */
  cloudnetCatalogue(): Promise<ApiResult<CloudNetCatalogue>>
  /** Each proxy's SignedVelocity state; staff only. Shown as a banner while a proxy lacks the relay. */
  proxyCapabilities(): Promise<ApiResult<ProxyCapabilities>>
  /** Every client rule, enabled or not, with its hit count; any signed-in role may read them. */
  clientRules(): Promise<ApiResult<ClientRule[]>>
  /** ADMIN only. */
  createClientRule(draft: ClientRuleDraft): Promise<ApiResult<ClientRule>>
  /** ADMIN only; replaces the rule's definition including whether it is enabled. */
  updateClientRule(draft: ClientRuleDraft): Promise<ApiResult<ClientRule>>
  /** ADMIN only. */
  deleteClientRule(key: string): Promise<ApiResult<ClientRule>>
  /** The newest hits of one rule; the proxy token is refused, staff roles may read. */
  clientRuleHits(key: string): Promise<ApiResult<ClientRuleHit[]>>
  /** The newest rule hits of one player across all rules. */
  clientRuleHitsOfOwner(owner: string): Promise<ApiResult<ClientRuleHit[]>>
  /** What the proxies saw (brands and channels from at least three players, commands, protocols); only suggests, never required. */
  clientRuleCatalogue(): Promise<ApiResult<ClientRuleCatalogue>>
  /** All reports, newest first. */
  reports(): Promise<ApiResult<Report[]>>
  resolveReport(identifier: string, resolution: ReportResolution): Promise<ApiResult<Report>>
  /** One report; NOT_FOUND if there is none. */
  report(identifier: string): Promise<ApiResult<Report>>
  /** Every report against the player (owner hash), newest first. */
  reportsAgainst(reportedHash: string): Promise<ApiResult<Report[]>>
  /** All appeals, newest first. */
  appeals(): Promise<ApiResult<Appeal[]>>
  reviewAppeal(identifier: string, decision: AppealDecision): Promise<ApiResult<Appeal>>
  /** What applying the template would do right now; changes nothing. */
  previewApply(owner: string, templateKey: string): Promise<ApiResult<ApplyPreview>>
  applyTemplate(owner: string, templateKey: string): Promise<ApiResult<ApplyResult>>
  revoke(owner: string, section: string): Promise<ApiResult<RevokeResult>>
  /** Takes one player off the network, a server or a section. The answer is immediate; poll {@link kick} for the outcome. A repeated key answers the same kick. */
  kickPlayer(request: KickRequest, idempotencyKey: string): Promise<ApiResult<KickAccepted>>
  /** ADMIN only. Kicks everybody from a server or section in one audited action. */
  kickMass(request: KickMassRequest, idempotencyKey: string): Promise<ApiResult<KickAccepted>>
  /** A kick and how it went; PENDING until a proxy answers or the deadline passes. */
  kick(id: string): Promise<ApiResult<KickResult>>
  /** Other owners that shared a hashed network address with this one; hashes only, never an IP. */
  evasionSightings(owner: string): Promise<ApiResult<EvasionSightings>>
  adjustElo(owner: string, change: { section: string; delta: number; note: string }): Promise<ApiResult<EloAdjustResult>>
  /**
   * One page of a punishment's evidence (identifier or short id), oldest first. Lifted and revoked
   * items are only returned with `includeInactive`, which the backend audits.
   */
  evidence(punishment: string, query?: { includeInactive?: boolean; page?: number; size?: number }): Promise<ApiResult<EvidencePage>>
  /** How many valid evidence items the punishment has; lifted and revoked ones are not counted. */
  evidenceCount(punishment: string): Promise<ApiResult<number>>
  evidenceItem(identifier: string): Promise<ApiResult<Evidence>>
  /** Attaches a LINK or REPLAY, `url` must be http(s). */
  addLinkEvidence(punishment: string, link: { kind: 'LINK' | 'REPLAY'; url: string; description?: string }): Promise<ApiResult<Evidence>>
  /** Deletes the item and its file; the reason is mandatory and audited by the backend. */
  deleteEvidence(identifier: string, reason: { reason: EvidenceDeleteReason; note?: string }): Promise<ApiResult<number>>
  /** The stored file of an item; fetched with the bearer token because `<img>` and `<video>` cannot send headers. */
  evidenceContent(identifier: string): Promise<ApiResult<Blob>>
  /** Uploads one file to the punishment; resolves through {@link Upload.result}, can be cancelled. */
  startEvidenceUpload(punishment: string, file: File, onProgress?: (fraction: number) => void): Upload
  /** Issues a one-time code (shown as `ABCD-EFGH`) that links the caller's Minecraft account when typed in game; replaces an earlier code. */
  issueStaffLinkCode(): Promise<ApiResult<StaffLinkCode>>
  /** The current appeal conditions (any team role). */
  appealConditions(): Promise<ApiResult<AppealConditions>>
  /** Every version of the appeal conditions, newest first. */
  appealConditionVersions(): Promise<ApiResult<AppealConditions[]>>
  /** Saves the text as a new version (ADMIN); a version is never overwritten. */
  saveAppealConditions(text: string): Promise<ApiResult<AppealConditions>>
  /** Whether the caller has a linked Minecraft account. */
  staffLinkStatus(): Promise<ApiResult<StaffLinkStatus>>
  /** Removes the caller's own link. */
  unlinkOwnStaffLink(): Promise<ApiResult<void>>
  /** All links of all staff (ADMIN only). */
  staffLinks(): Promise<ApiResult<StaffLink[]>>
  /** Removes anyone's link (ADMIN only). */
  unlinkStaffLink(oid: string): Promise<ApiResult<void>>
  /** Issues a one-time code (shown as `ABCD-EFGH`) that links the caller's Discord account when typed as /link in Discord; replaces an earlier code. */
  issueDiscordLinkCode(): Promise<ApiResult<DiscordLinkCode>>
  /** Whether the caller has a linked Discord account (never a Discord id). */
  discordLinkStatus(): Promise<ApiResult<DiscordLinkStatus>>
  /** Removes the caller's own Discord link. */
  unlinkOwnDiscordLink(): Promise<ApiResult<void>>
  /** Every staff and player Discord link, without Discord ids (ADMIN only). */
  discordLinks(): Promise<ApiResult<DiscordLink[]>>
  /** Removes one Discord link (ADMIN only). */
  removeDiscordLink(id: string): Promise<ApiResult<void>>
  /** Every known staff principal, most recently seen first (ADMIN only). */
  staffActors(): Promise<ApiResult<StaffActor[]>>
  /** Removes the principal's display name and Minecraft link; the `oid` and all history stay (ADMIN only, irreversible). */
  anonymizeStaffActor(oid: string): Promise<ApiResult<void>>
  /** The staff audit log, newest first, one cursor page (ADMIN only). Read only. */
  auditLog(query?: AuditQuery): Promise<ApiResult<AuditPage>>
  /**
   * Imports a vanilla `banned-players.json` (and optionally `banned-ips.json`), or with `dryRun`
   * only reports what would happen (ADMIN only). Invalid entries are reported, not fatal.
   */
  importVanillaBans(request: VanillaImportRequest): Promise<ApiResult<VanillaImportResult>>
  /** Imported punishments that have no template yet, grouped by original reason (ADMIN only). */
  importedPunishmentGroups(): Promise<ApiResult<ImportedPunishmentGroups>>
  /**
   * Links the imported punishments that have no template and the original `reason` to a template
   * (ADMIN only). Only the template changes; repeating it links nothing.
   */
  retemplateImportedPunishments(request: { templateKey: string; reason: string }): Promise<ApiResult<ImportedPunishmentRetemplateResult>>

  /** One page of the currently active punishments of all players, newest first unless sorted otherwise. */
  activePunishments(query?: ActivePunishmentQuery): Promise<ApiResult<ActivePunishmentPage>>

  /** One page of cases, newest activity first. */
  cases(query?: CaseListQuery): Promise<ApiResult<CaseListPage>>
  /** The punishments whose short id (`#` and eight hex characters) matches; several if short ids collide, none if nothing matches. */
  punishmentsByShortId(shortId: string): Promise<ApiResult<Punishment[]>>
  /** A case by punishment identifier or short id; its `version` is what {@link updateCase} must send back. */
  caseDetail(reference: string): Promise<ApiResult<Case>>
  /** One page of the case timeline; pass the previous page's `nextCursor` to continue. */
  caseTimeline(reference: string, query?: CaseTimelineQuery): Promise<ApiResult<CaseTimelinePage>>
  /**
   * Everything the conversation needs, oldest first: the messages with their edits, deletions and
   * redactions, and the Ban ID leak events. Follows the cursor until the end (bounded).
   */
  caseConversation(reference: string): Promise<ApiResult<CaseTimelineItem[]>>
  /** Changes staff metadata; a stale `expectedVersion` answers 409 and changes nothing. */
  updateCase(reference: string, change: CaseUpdate): Promise<ApiResult<Case>>
  /** Adds a staff note (1 to 4000 characters, no UUID or IP); resolves to the new note id. */
  addCaseNote(reference: string, text: string): Promise<ApiResult<{ noteId: string; seq: number }>>
  editCaseNote(reference: string, noteId: string, text: string): Promise<ApiResult<{ noteId: string; seq: number }>>
  /** Removes the text of every version of a note (ADMIN only). */
  redactCaseNote(reference: string, noteId: string, reason: NoteRedactionReason): Promise<ApiResult<{ noteId: string; redactedVersions: number }>>

  /**
   * Replies to the player (1 to 2000 characters, no UUID, IP or Ban ID; the server rejects those). The same
   * `idempotencyKey` makes a retry a no-op that answers with the first message.
   */
  postCaseMessage(reference: string, text: string, idempotencyKey?: string, attachmentIds?: string[]): Promise<ApiResult<CaseMessageResult>>
  /**
   * Stages one file (PNG, JPEG, WebP, PDF or text, 10 MiB at most) for the case's next message. The
   * player sees it once that message is posted. Nothing is staged for a file the server refuses.
   */
  uploadCaseAttachment(reference: string, file: File): Promise<ApiResult<ChatAttachment>>
  /** The team count and the player-supplied count of a punishment's valid evidence. */
  evidenceCounts(punishment: string): Promise<ApiResult<EvidenceCounts>>
  /** Every share of the case, oldest first, with its state. */
  evidenceShares(reference: string): Promise<ApiResult<EvidenceShare[]>>
  /** What the player would see for a selection; changes nothing. */
  previewEvidenceShare(reference: string, request: EvidenceSharePreviewRequest): Promise<ApiResult<EvidenceSharePreview>>
  /** Shows evidence to the player; `confirmedMinimised` must be true. An identical active share answers the existing one. */
  shareEvidence(reference: string, request: EvidenceShareRequest): Promise<ApiResult<EvidenceShare>>
  /** Withdraws a share at once; the player's next read shows it as withdrawn. */
  revokeEvidenceShare(reference: string, shareId: string, reason: ShareRevokeReason): Promise<ApiResult<EvidenceShare>>
  /**
   * Live updates of one case: `onChanged` gets the seq of every change the reader may see, then the case is read again.
   * The stream reconnects by itself until it is closed; a missing or refused stream leaves polling to the caller.
   */
  openCaseEvents(reference: string, handlers: { onChanged: (seq: number) => void; onState?: (state: CaseEventsState) => void }): CaseEvents
  /** Corrects a staff message (its author or an admin); earlier versions stay in the timeline. */
  editCaseMessage(reference: string, messageId: string, text: string): Promise<ApiResult<CaseMessageResult>>
  /** Deletes a staff message (its author or an admin); staff still see the last text marked deleted. */
  deleteCaseMessage(reference: string, messageId: string): Promise<ApiResult<CaseMessageResult>>
  /** Removes the text of every version of a message for data protection (ADMIN only). */
  redactCaseMessage(reference: string, messageId: string, reason: NoteRedactionReason): Promise<ApiResult<CaseMessageRedactResult>>
  /** Redacts every message the player wrote in this case (ADMIN only); staff messages stay. */
  eraseCasePlayerMessages(reference: string, reason: NoteRedactionReason): Promise<ApiResult<CaseTranscriptEraseResult>>
  /**
   * Erases a Discord participant of the case (ADMIN only): the stored Discord id and name go, the timeline
   * keeps an anonymous author. The participant id comes from a message's author, never a Discord id.
   */
  eraseCaseParticipant(reference: string, participantId: string): Promise<ApiResult<CaseParticipantErasure>>
  /** The readable protocol versions of every appeal of the case, newest first. */
  caseProtocols(reference: string): Promise<ApiResult<CaseProtocolVersion[]>>
  /** The ids of the appeals of the case that were decided (at most 200), to tell a missing protocol from none. */
  caseDecidedAppeals(reference: string): Promise<ApiResult<string[]>>
  /** The plain text of one stored protocol version; the server records a VIEWED entry. */
  openCaseProtocol(reference: string, evidenceId: string): Promise<ApiResult<CaseProtocolText>>
  /** One stored protocol version as a file; the server records a DOWNLOADED entry. */
  downloadCaseProtocol(reference: string, evidenceId: string, format: TranscriptFormat): Promise<ApiResult<TranscriptFile>>
  /** Marks the Ban ID leaked (MODERATOR and above). The Ban ID is in neither request nor response. */
  markBanIdLeaked(reference: string, reason: BanIdLeakReason): Promise<ApiResult<CaseBanIdLeakResult>>
  /** Replaces a leaked Ban ID (ADMIN only). The new one is not returned: {@link revealBanId} shows it. */
  reissueBanId(reference: string, reason: BanIdReissueReason): Promise<ApiResult<CaseBanIdReissueResult>>
  /** The current Ban ID of a punishment (MODERATOR and above). A credential: show it, never store or log it. */
  revealBanId(reference: string): Promise<ApiResult<string>>
  /** The conversation and lifecycle of the case as a file (ADMIN only); notes only with `includeNotes`. */
  exportCaseTranscript(reference: string, format: TranscriptFormat, includeNotes: boolean): Promise<ApiResult<TranscriptFile>>
}

const EVIDENCE_PAGE_SIZE = 20

const PAGE_SIZE = 200
/** What the conversation reads of the timeline: a type group and an exact type. */
/** `ticket` is the Discord ticket lifecycle, `protocol.stored` the stored protocol versions, `appeal.decided` closes a ticket. */
const CONVERSATION_TYPES = ['message', 'ban_id.leak_marked', 'ticket', 'protocol.stored', 'appeal.decided', 'evidence.shared', 'evidence.share_revoked']
const CONVERSATION_PAGE_SIZE = 200
const CONVERSATION_MAX_PAGES = 10
const MAX_PAGES = 25

/**
 * The spec documents paged endpoints as arrays and their `pageable` parameter as an object, but on
 * the wire they take `page`/`size` and answer with Micronaut's Page. Accepts both shapes.
 */
export function pageContent<T>(body: unknown): { items: T[]; totalPages: number } {
  if (Array.isArray(body)) return { items: body as T[], totalPages: 1 }
  if (typeof body === 'object' && body !== null) {
    const page = body as { content?: unknown; totalPages?: unknown }
    if (Array.isArray(page.content)) {
      return { items: page.content as T[], totalPages: typeof page.totalPages === 'number' ? page.totalPages : 1 }
    }
  }
  return { items: [], totalPages: 1 }
}

/** Query string for one page; sent through openapi-fetch's serializer because the spec cannot express it. */
function pageQuery(page: number, extra: Record<string, string> = {}): () => string {
  return () => new URLSearchParams({ ...extra, page: String(page), size: String(PAGE_SIZE) }).toString()
}

/** Query string of the evidence list. `includeInactive` is only sent when true so the default request is the plain one. */
function evidenceQuery(query: { includeInactive?: boolean; page?: number; size?: number }): () => string {
  return () => {
    const params = new URLSearchParams({ page: String(query.page ?? 0), size: String(query.size ?? EVIDENCE_PAGE_SIZE) })
    if (query.includeInactive) params.set('includeInactive', 'true')
    return params.toString()
  }
}

/** Query string of the timeline; `type` repeats once per type or group. */
function timelineQuery(query: CaseTimelineQuery): () => string {
  return () => {
    const params = new URLSearchParams()
    if (query.cursor) params.set('cursor', query.cursor)
    if (query.size) params.set('size', String(query.size))
    for (const type of query.types ?? []) params.append('type', type)
    if (query.actorTrust) params.set('actorTrust', query.actorTrust)
    if (query.from !== undefined) params.set('from', String(query.from))
    if (query.to !== undefined) params.set('to', String(query.to))
    if (query.order) params.set('order', query.order)
    return params.toString()
  }
}

/** Only the filters that are set are sent. */
function caseListQuery(query: object): () => string {
  return () => {
    const params = new URLSearchParams()
    for (const [key, value] of Object.entries(query)) if (value !== undefined && value !== '') params.set(key, String(value))
    return params.toString()
  }
}

const NO_PAGEABLE = {} as components['schemas']['Pageable']

async function collectPages<T>(fetchPage: (page: number) => Promise<ApiResult<unknown>>): Promise<ApiResult<T[]>> {
  const items: T[] = []
  for (let page = 0; page < MAX_PAGES; page++) {
    const result = await fetchPage(page)
    if (!result.ok) return result
    const content = pageContent<T>(result.data)
    items.push(...content.items)
    if (page + 1 >= content.totalPages) break
  }
  return { ok: true, data: items }
}

/** Blank optional text means "not set"; the backend then falls back to the template key / its default window. */
function templateBody(draft: TemplateDraft) {
  return {
    key: draft.key.trim(),
    reason: draft.reason.trim(),
    section: draft.section,
    escalationGroup: draft.escalationGroup?.trim() || null,
    escalationWindow: draft.escalationWindow?.trim() || null,
    warnWindow: draft.warnWindow?.trim() || null,
    // Only a punishment step has a duration; a kick and a warning are moments, so they are sent without one.
    steps: draft.steps.map((step) => {
      const kind = step.kind ?? 'PUNISH'
      return { kind, duration: kind === 'PUNISH' ? (step.duration ?? '').trim() : null, eloCost: step.eloCost }
    }),
  }
}

/**
 * Only the fields that belong to the kind and the action are sent, so the backend never has to reject a leftover:
 * a range for PROTOCOL, patterns for the others, negate for BRAND and PROTOCOL, hiding for COMMAND, the scope's
 * sections and servers for SERVERS, a message for DENY, a template (and the spoofable acknowledgement, which is
 * only asked for brand, mod and channel rules) for TEMPLATE.
 */
function clientRuleBody(draft: ClientRuleDraft) {
  const trimmed = (values: string[]) => values.map((value) => value.trim()).filter(Boolean)
  const protocol = draft.kind === 'PROTOCOL'
  const spoofable = draft.kind === 'BRAND' || draft.kind === 'MOD' || draft.kind === 'CHANNEL'
  const servers = draft.scope === 'SERVERS'
  return {
    key: draft.key.trim(),
    displayName: draft.displayName.trim(),
    kind: draft.kind,
    patterns: protocol ? [] : trimmed(draft.patterns),
    protocolMin: protocol ? draft.protocolMin : null,
    protocolMax: protocol ? draft.protocolMax : null,
    negate: (draft.kind === 'BRAND' || protocol) && draft.negate,
    scope: draft.scope,
    sectionKeys: servers ? trimmed(draft.sectionKeys) : [],
    targetKind: servers ? draft.targetKind : ('PATTERN' as const),
    servers: servers ? trimmed(draft.servers) : [],
    action: draft.action,
    denyMessage: draft.action === 'DENY' ? draft.denyMessage.trim() || null : null,
    templateKey: draft.action === 'TEMPLATE' ? draft.templateKey.trim() || null : null,
    acknowledgedSpoofable: draft.action === 'TEMPLATE' && spoofable && draft.acknowledgedSpoofable,
    hideCommand: draft.kind === 'COMMAND' && draft.hideCommand,
  }
}

/** The backend leaves empty lists out of its JSON. */
function withLists(rule: ClientRule): ClientRule {
  return { ...rule, patterns: rule.patterns ?? [], sectionKeys: rule.sectionKeys ?? [], servers: rule.servers ?? [], hitCount: rule.hitCount ?? 0 }
}

/** Server targets only exist for SERVER sections (other sections save as PATTERN with none); a blank soft threshold means the section has none. */
function sectionBody(draft: SectionDraft) {
  const hasSoft = draft.softThreshold !== null
  return {
    key: draft.key.trim(),
    displayName: draft.displayName.trim(),
    enforcement: draft.enforcement,
    targetKind: draft.enforcement === 'SERVER' ? draft.targetKind : ('PATTERN' as const),
    servers: draft.enforcement === 'SERVER' ? draft.servers.map((pattern) => pattern.trim()).filter(Boolean) : [],
    baseScore: draft.baseScore,
    softThreshold: hasSoft ? draft.softThreshold : null,
    softDuration: hasSoft ? draft.softDuration?.trim() || null : null,
    permanentThreshold: draft.permanentThreshold,
    pardonScore: draft.pardonScore,
    permanentReason: draft.permanentReason.trim(),
    decayPerDay: draft.decayPerDay,
  }
}

export function createBlackholeApi(options: ApiOptions): BlackholeApi {
  const http = createHttp({ baseUrl: options.baseUrl, auth: options.auth, fetch: options.fetch })

  return {
    me: () => toResult(http.GET('/auth/me')),

    dashboard: () => toResult(http.GET('/dashboard/summary')),

    inbox: () => toResult(http.GET('/inbox')),

    async resolvePlayer(name) {
      const result = await toResult(http.GET('/player/resolve/{name}', { params: { path: { name } } }))
      if (!result.ok) return result
      const owner = result.data.owner
      if (!owner) {
        return { ok: false, error: { status: 500, message: 'The backend returned no owner hash', kind: 'UNKNOWN' } }
      }
      return { ok: true, data: { ...result.data, owner } }
    },

    async profile(owner) {
      const result = await toResult(http.GET('/profile/{owner}', { params: { path: { owner } } }))
      if (!result.ok) {
        // No profile yet simply means the player was never punished.
        return result.error.status === 404 ? { ok: true, data: { active: {}, history: [] } } : result
      }
      return map(result, (profile) => ({ active: profile.active ?? {}, history: profile.history ?? [] }))
    },

    async eloProfile(owner) {
      const result = await toResult(http.GET('/elo/{owner}', { params: { path: { owner } } }))
      return map(result, (profile) => ({ ...profile, sections: profile.sections ?? [] }))
    },

    async eloHistory(owner, section) {
      const events = await collectPages<EloEvent>((page) =>
        toResult(
          http.GET('/elo/{owner}/history', {
            params: { path: { owner }, query: { section, pageable: NO_PAGEABLE } },
            querySerializer: pageQuery(page, { section }),
          }),
        ),
      )
      return map(events, (all) => [...all].sort((a, b) => b.createdAt - a.createdAt))
    },

    async templates() {
      const templates = await collectPages<Template>((page) =>
        toResult(http.GET('/template', { params: { query: { pageable: NO_PAGEABLE } }, querySerializer: pageQuery(page) })),
      )
      return map(templates, (all) => all.map((template) => ({ ...template, steps: template.steps ?? [] })))
    },

    createTemplate: (draft) => toResult(http.POST('/template', { body: templateBody(draft) })),

    updateTemplate: (identifier, draft) =>
      toResult(http.POST('/template/update', { body: { identifier, ...templateBody(draft) } })),

    deleteTemplate: (identifier) =>
      toResult(http.DELETE('/template/delete/{identifier}', { params: { path: { identifier } } })),

    async sections() {
      const result = await toResult(http.GET('/section'))
      // The backend leaves empty lists out of its JSON (a BAN or MUTE section has no server patterns).
      return map(result, (all) => (all ?? []).map((section) => ({ ...section, servers: section.servers ?? [] })))
    },

    createSection: (draft) => toResult(http.POST('/section', { body: sectionBody(draft) })),

    updateSection: (draft) =>
      toResult(http.POST('/section/update', { body: { ...sectionBody(draft), decayPerDay: draft.decayPerDay, enabled: draft.enabled } })),

    disableSection: (key) => toResult(http.POST('/section/{key}/disable', { params: { path: { key } } })),

    async cloudnetCatalogue() {
      const result = await toResult(http.GET('/cloudnet/catalogue'))
      // The backend leaves empty lists out of its JSON.
      return map(result, (catalogue) => ({
        ...catalogue,
        tasks: catalogue.tasks ?? [],
        groups: catalogue.groups ?? [],
        services: catalogue.services ?? [],
        proxies: catalogue.proxies ?? [],
      }))
    },

    async proxyCapabilities() {
      const result = await toResult(http.GET('/proxy/capabilities'))
      // The backend leaves an empty list out of its JSON.
      return map(result, (capabilities) => ({ proxies: capabilities.proxies ?? [] }))
    },

    async clientRules() {
      const result = await toResult(http.GET('/client-rule'))
      return map(result, (all) => (all ?? []).map(withLists))
    },

    async createClientRule(draft) {
      return map(await toResult(http.POST('/client-rule', { body: clientRuleBody(draft) })), withLists)
    },

    async updateClientRule(draft) {
      const body = { ...clientRuleBody(draft), enabled: draft.enabled }
      return map(await toResult(http.PUT('/client-rule/{key}', { params: { path: { key: body.key } }, body })), withLists)
    },

    async deleteClientRule(key) {
      return map(await toResult(http.DELETE('/client-rule/{key}', { params: { path: { key } } })), withLists)
    },

    async clientRuleHits(key) {
      const result = await toResult(http.GET('/client-rule/{key}/hits', { params: { path: { key } } }))
      return map(result, (found) => found.hits ?? [])
    },

    async clientRuleHitsOfOwner(owner) {
      const result = await toResult(http.GET('/client-rule/hits', { params: { query: { owner } } }))
      return map(result, (found) => found.hits ?? [])
    },

    async clientRuleCatalogue() {
      const result = await toResult(http.GET('/client-rule/catalogue'))
      // The backend leaves empty lists out of its JSON.
      return map(result, (catalogue) => ({
        ...catalogue,
        brands: catalogue.brands ?? [],
        channels: catalogue.channels ?? [],
        commands: catalogue.commands ?? [],
        protocols: catalogue.protocols ?? [],
        proxies: catalogue.proxies ?? [],
      }))
    },

    async reports() {
      const reports = await collectPages<Report>((page) =>
        toResult(http.GET('/report', { params: { query: { pageable: NO_PAGEABLE } }, querySerializer: pageQuery(page) })),
      )
      return map(reports, (all) => [...all].sort((a, b) => b.createdAt - a.createdAt))
    },

    report: (identifier) => toResult(http.GET('/report/{identifier}', { params: { path: { identifier } } })),

    async reportsAgainst(reportedHash) {
      const reports = await collectPages<Report>((page) =>
        toResult(
          http.GET('/report', {
            params: { query: { pageable: NO_PAGEABLE, reportedHash } },
            querySerializer: pageQuery(page, { reportedHash }),
          }),
        ),
      )
      return map(reports, (all) => [...all].sort((a, b) => b.createdAt - a.createdAt))
    },

    resolveReport: (identifier, resolution) =>
      toResult(
        http.POST('/report/{identifier}/resolve', {
          params: { path: { identifier } },
          body: {
            status: resolution.status,
            resolutionNote: resolution.note ?? null,
            punishmentTemplateId: resolution.templateId ?? null,
          },
        }),
      ),

    async appeals() {
      const appeals = await collectPages<Appeal>((page) =>
        toResult(http.GET('/appeal', { params: { query: { pageable: NO_PAGEABLE } }, querySerializer: pageQuery(page) })),
      )
      return map(appeals, (all) => [...all].sort((a, b) => b.createdAt - a.createdAt))
    },

    reviewAppeal: (identifier, decision) =>
      toResult(
        http.POST('/appeal/{identifier}/review', {
          params: { path: { identifier } },
          body: {
            decision: decision.decision,
            decisionNote: decision.note ?? null,
            newExpirationAt: decision.newExpirationAt ?? null,
            playerMessage: decision.playerMessage,
          },
        }),
      ),

    previewApply: (owner, templateKey) =>
      toResult(http.POST('/punishment/preview', { body: { owner, templateKey } })),

    applyTemplate: (owner, templateKey) =>
      toResult(http.POST('/punishment/apply', { body: { owner, templateKey } })),

    revoke: (owner, section) =>
      toResult(http.POST('/punishment/revoke', { body: { owner, section } })),

    kickPlayer: (request, idempotencyKey) =>
      toResult(http.POST('/kick', { params: { header: { 'Idempotency-Key': idempotencyKey } }, body: request })),

    kickMass: (request, idempotencyKey) =>
      toResult(http.POST('/kick/mass', { params: { header: { 'Idempotency-Key': idempotencyKey } }, body: request })),

    kick: (id) => toResult(http.GET('/kick/{id}', { params: { path: { id } } })),

    async evasionSightings(owner) {
      const result = await toResult(http.GET('/evasion/{owner}/sightings', { params: { path: { owner } } }))
      return map(result, (sightings) => ({ ...sightings, linkedOwners: sightings.linkedOwners ?? [] }))
    },

    adjustElo: (owner, change) =>
      toResult(
        http.POST('/elo/{owner}/adjust', {
          params: { path: { owner } },
          body: {
            section: change.section,
            delta: change.delta,
            note: change.note.slice(0, MAX_NOTE_LENGTH),
          },
        }),
      ),

    async evidence(punishment, query = {}) {
      const result = await toResult(
        http.GET('/punishment/{reference}/evidence', {
          params: { path: { reference: punishment }, query: { includeInactive: false, pageable: NO_PAGEABLE } },
          querySerializer: evidenceQuery(query),
        }),
      )
      // Like other lists, an empty one may come without its array.
      return map(result, (page) => ({ ...page, items: page.items ?? [] }))
    },

    async evidenceCount(punishment) {
      const result = await toResult(http.GET('/punishment/{reference}/evidence/count', { params: { path: { reference: punishment } } }))
      return map(result, (body) => body.count)
    },

    evidenceItem: (identifier) =>
      toResult(http.GET('/evidence/{identifier}', { params: { path: { identifier } } })),

    addLinkEvidence: (punishment, link) =>
      toResult(
        http.POST('/evidence', {
          body: {
            punishment,
            kind: link.kind,
            sourceSystem: EVIDENCE_SOURCE_SYSTEM,
            reference: link.url.trim(),
            // The spec types payload values as objects; a link's description is a plain string.
            payload: (link.description?.trim() ? { description: link.description.trim() } : null) as components['schemas']['EvidenceCreateRequest']['payload'],
          },
        }),
      ),

    async deleteEvidence(identifier, change) {
      const result = await toResult(
        http.DELETE('/evidence/{identifier}', {
          params: {
            path: { identifier },
            query: { reason: change.reason, note: change.note?.trim() || null },
          },
        }),
      )
      return map(result, (body) => body.deleted)
    },

    startEvidenceUpload: (punishment, file, onProgress) =>
      uploadEvidence({ baseUrl: options.baseUrl, auth: options.auth, punishment, file, onProgress }),

    evidenceContent: (identifier) =>
      toResult(http.GET('/evidence/{identifier}/content', { params: { path: { identifier } }, parseAs: 'blob' })),

    appealConditions: () => toResult(http.GET('/appeal-conditions')),
    appealConditionVersions: async () => map(await toResult(http.GET('/appeal-conditions/versions')), (all) => all ?? []),
    saveAppealConditions: (text) => toResult(http.POST('/appeal-conditions', { body: { text } })),

    issueStaffLinkCode: () => toResult(http.POST('/staff-link/code')),
    staffLinkStatus: () => toResult(http.GET('/staff-link/me')),
    unlinkOwnStaffLink: async () => map(await toResult(http.DELETE('/staff-link/me')), () => undefined),
    staffLinks: async () => map(await toResult(http.GET('/admin/staff-link')), (body) => body.items),
    unlinkStaffLink: async (oid) =>
      map(await toResult(http.DELETE('/admin/staff-link/{oid}', { params: { path: { oid } } })), () => undefined),

    issueDiscordLinkCode: () => toResult(http.POST('/staff-discord-link/code')),
    discordLinkStatus: () => toResult(http.GET('/staff-discord-link/me')),
    unlinkOwnDiscordLink: async () => map(await toResult(http.DELETE('/staff-discord-link/me')), () => undefined),
    discordLinks: async () => map(await toResult(http.GET('/admin/discord-link')), (body) => body.items),
    removeDiscordLink: async (id) =>
      map(await toResult(http.DELETE('/admin/discord-link/{id}', { params: { path: { id } } })), () => undefined),

    async importVanillaBans(request) {
      // Hidden from the published spec, so it cannot go through the typed client.
      const form = new FormData()
      form.append('bannedPlayers', request.players, 'banned-players.json')
      if (request.ips) form.append('bannedIps', request.ips, 'banned-ips.json')
      if (request.reasonTemplates && Object.keys(request.reasonTemplates).length > 0) {
        form.append('reasonTemplates', JSON.stringify(request.reasonTemplates))
      }
      const send = authenticatedFetch(options.auth, (options.fetch ?? ((r: Request) => globalThis.fetch(r))) as typeof fetch)
      try {
        const response = await send(
          new Request(`${options.baseUrl}/admin/import/vanilla?dryRun=${request.dryRun}&templateKey=${encodeURIComponent(request.templateKey)}`, {
            method: 'POST',
            headers: { [API_VERSION_HEADER]: API_VERSION },
            body: form,
          }),
        )
        const text = await response.text()
        let body: unknown = text
        try {
          body = JSON.parse(text)
        } catch {
          // A plain text error body is shown as it is.
        }
        if (!response.ok) return { ok: false, error: errorFromBody(response.status, body) }
        const result = body as VanillaImportResult
        return { ok: true, data: { ...result, invalidEntries: result.invalidEntries ?? [], reasonGroups: result.reasonGroups ?? [] } }
      } catch (cause) {
        return { ok: false, error: { status: 0, message: cause instanceof Error ? cause.message : String(cause), kind: 'NETWORK' } }
      }
    },

    importedPunishmentGroups: () => toResult(http.GET('/admin/import/punishments')),
    retemplateImportedPunishments: ({ templateKey, reason }) =>
      toResult(http.POST('/admin/import/punishments/retemplate', { body: { templateKey, reason } })),

    staffActors: async () => map(await toResult(http.GET('/admin/staff-actor')), (body) => body.items ?? []),
    anonymizeStaffActor: async (oid) =>
      map(await toResult(http.POST('/admin/staff-actor/{oid}/anonymize', { params: { path: { oid } } })), () => undefined),

    auditLog: (query = {}) => toResult(http.GET('/admin/audit', { params: { query } })),

    activePunishments: (query = {}) =>
      toResult(http.GET('/punishment/active', { querySerializer: caseListQuery(query) })),

    cases: (query = {}) =>
      toResult(http.GET('/case', { querySerializer: caseListQuery(query) })),

    punishmentsByShortId: async (shortId) =>
      map(await toResult(http.GET('/punishment/short/{shortId}', { params: { path: { shortId } } })), (all) => all ?? []),

    caseDetail: (reference) => toResult(http.GET('/case/{reference}', { params: { path: { reference } } })),

    caseTimeline: (reference, query = {}) =>
      toResult(http.GET('/case/{reference}/timeline', { params: { path: { reference } }, querySerializer: timelineQuery(query) })),

    caseConversation: async (reference) => {
      const items: CaseTimelineItem[] = []
      let cursor: string | undefined
      for (let page = 0; page < CONVERSATION_MAX_PAGES; page += 1) {
        const result = await toResult(
          http.GET('/case/{reference}/timeline', {
            params: { path: { reference } },
            querySerializer: timelineQuery({ types: CONVERSATION_TYPES, order: 'asc', size: CONVERSATION_PAGE_SIZE, cursor }),
          }),
        )
        if (!result.ok) return result
        items.push(...result.data.items)
        if (!result.data.nextCursor) break
        cursor = result.data.nextCursor
      }
      return { ok: true, data: items }
    },

    updateCase: (reference, change) =>
      toResult(
        http.PATCH('/case/{reference}', {
          params: { path: { reference } },
          body: {
            expectedVersion: change.expectedVersion,
            status: change.status,
            priority: change.priority,
            tags: change.tags,
            // An absent assigneeOid cannot say "remove", so null becomes the explicit flag.
            assigneeOid: change.assigneeOid ?? undefined,
            clearAssignee: change.assigneeOid === null,
          },
        }),
      ),

    addCaseNote: (reference, text) =>
      toResult(http.POST('/case/{reference}/notes', { params: { path: { reference } }, body: { text } })),

    editCaseNote: (reference, noteId, text) =>
      toResult(http.PUT('/case/{reference}/notes/{noteId}', { params: { path: { reference, noteId } }, body: { text } })),

    redactCaseNote: (reference, noteId, reason) =>
      toResult(http.POST('/case/{reference}/notes/{noteId}/redact', { params: { path: { reference, noteId } }, body: { reason } })),
 
    postCaseMessage: (reference, text, idempotencyKey, attachmentIds) =>
      toResult(
        http.POST('/case/{reference}/messages', {
          params: { path: { reference }, header: { 'Idempotency-Key': idempotencyKey ?? null } },
          body: attachmentIds?.length ? { text, attachmentIds } : { text },
        }),
      ),

    uploadCaseAttachment: (reference, file) => {
      // Sent as multipart form data; openapi-fetch passes a FormData body through unchanged.
      const form = new FormData()
      form.append('file', file, file.name)
      return toResult(
        http.POST('/case/{reference}/attachments', {
          params: { path: { reference } },
          body: form as unknown as { file?: string },
        }),
      )
    },

    evidenceCounts: (punishment) => toResult(http.GET('/punishment/{reference}/evidence/count', { params: { path: { reference: punishment } } })),

    evidenceShares: async (reference) =>
      map(await toResult(http.GET('/case/{reference}/evidence-shares', { params: { path: { reference } } })), (body) => body.items),

    previewEvidenceShare: (reference, request) =>
      toResult(http.POST('/case/{reference}/evidence-shares/preview', { params: { path: { reference } }, body: request })),

    shareEvidence: (reference, request) =>
      toResult(http.POST('/case/{reference}/evidence-shares', { params: { path: { reference } }, body: request })),

    openCaseEvents: (reference, handlers) =>
      openCaseEvents({ baseUrl: options.baseUrl, auth: options.auth, reference, fetch: options.fetch, ...handlers }),

    revokeEvidenceShare: (reference, shareId, reason) =>
      toResult(http.POST('/case/{reference}/evidence-shares/{shareId}/revoke', { params: { path: { reference, shareId } }, body: { reason } })),

    editCaseMessage: (reference, messageId, text) =>
      toResult(http.PUT('/case/{reference}/messages/{messageId}', { params: { path: { reference, messageId } }, body: { text } })),

    deleteCaseMessage: (reference, messageId) =>
      toResult(http.DELETE('/case/{reference}/messages/{messageId}', { params: { path: { reference, messageId } } })),

    redactCaseMessage: (reference, messageId, reason) =>
      toResult(http.POST('/case/{reference}/messages/{messageId}/redact', { params: { path: { reference, messageId } }, body: { reason } })),

    eraseCasePlayerMessages: (reference, reason) =>
      toResult(http.POST('/case/{reference}/transcript/erase-player', { params: { path: { reference } }, body: { reason } })),

    eraseCaseParticipant: (reference, participantId) =>
      toResult(http.POST('/case/{reference}/participants/{participantId}/erase', { params: { path: { reference, participantId } } })),

    caseProtocols: async (reference) =>
      map(await toResult(http.GET('/case/{reference}/protocol', { params: { path: { reference } } })), (body) => body.items),

    caseDecidedAppeals: async (reference) =>
      map(
        await toResult(http.GET('/case/{reference}/timeline', { params: { path: { reference } }, querySerializer: timelineQuery({ types: ['appeal.decided'], size: 200 }) })),
        (page) => page.items.map((event) => (event.payload as { appealId?: unknown }).appealId).filter((id): id is string => typeof id === 'string'),
      ),

    openCaseProtocol: (reference, evidenceId) =>
      toResult(http.GET('/case/{reference}/protocol/{evidenceId}', { params: { path: { reference, evidenceId } } })),

    downloadCaseProtocol: async (reference, evidenceId, format) => {
      const result = await toResult(
        http.GET('/case/{reference}/protocol/{evidenceId}/download', {
          params: { path: { reference, evidenceId } },
          querySerializer: () => new URLSearchParams({ format }).toString(),
          parseAs: 'text',
        }),
      )
      return map(result, (content) => ({ format, content: String(content), mediaType: format === 'json' ? 'application/json' : 'text/plain' }))
    },

    markBanIdLeaked: (reference, reason) =>
      toResult(http.POST('/case/{reference}/ban-id/leak', { params: { path: { reference } }, body: { reason } })),

    reissueBanId: (reference, reason) =>
      toResult(http.POST('/case/{reference}/ban-id/reissue', { params: { path: { reference } }, body: { reason } })),

    revealBanId: async (reference) =>
      map(await toResult(http.GET('/punishment/{reference}/ban-id', { params: { path: { reference } } })), (body) => body.banId),

    exportCaseTranscript: async (reference, format, includeNotes) => {
      const result = await toResult(
        http.GET('/case/{reference}/transcript', {
          params: { path: { reference } },
          querySerializer: () => new URLSearchParams({ format, includeNotes: String(includeNotes) }).toString(),
          parseAs: 'text',
        }),
      )
      return map(result, (content) => ({ format, content: String(content), mediaType: format === 'json' ? 'application/json' : 'text/plain' }))
    },
  }
}
