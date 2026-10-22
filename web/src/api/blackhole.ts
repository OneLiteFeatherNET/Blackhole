import { createHttp, map, toResult } from './http'
import type { components } from './schema'
import { EVIDENCE_SOURCE_SYSTEM, uploadEvidence, type Upload } from './uploadEvidence'
import type {
  ApiResult,
  ApplyPreview,
  Appeal,
  AppealDecision,
  ApplyResult,
  DashboardSummary,
  EloAdjustResult,
  EloEvent,
  EloProfile,
  Evidence,
  EvidenceDeleteReason,
  EvidencePage,
  PunishmentProfile,
  Report,
  ReportResolution,
  ResolvedPlayer,
  RevokeResult,
  Section,
  SectionDraft,
  Template,
  TemplateDraft,
} from './types'

export interface ApiOptions {
  baseUrl: string
  /** The operator UUID from /config.json, attached to every write as the acting source. */
  source: string
  /** The UUID sent as `reviewerId` when deciding an appeal; defaults to `source`. */
  reviewer?: string
  /** The free-text "acting as" label; prefixed to the notes the UI writes. Read on every call. */
  actingAs?: () => string
  fetch?: typeof fetch
}

/** Staff-written notes are limited to 500 characters by the backend. */
export const MAX_NOTE_LENGTH = 500

/**
 * Prefixes a note with the unverified "acting as" label, so the audit trail shows who the operator
 * says they are. Truncates to the backend's limit.
 */
export function withActor(note: string, actingAs: string): string {
  const label = actingAs.trim()
  const text = label ? `[${label}] ${note}` : note
  return text.slice(0, MAX_NOTE_LENGTH)
}

/**
 * The calls the UI makes, one method per use case. Every method resolves to an {@link ApiResult}
 * and never throws, so screens branch on `ok` instead of wrapping calls in try/catch.
 */
export interface BlackholeApi {
  dashboard(): Promise<ApiResult<DashboardSummary>>
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
  /** All reports, newest first. */
  reports(): Promise<ApiResult<Report[]>>
  resolveReport(identifier: string, resolution: ReportResolution): Promise<ApiResult<Report>>
  /** All appeals, newest first. */
  appeals(): Promise<ApiResult<Appeal[]>>
  reviewAppeal(identifier: string, decision: AppealDecision): Promise<ApiResult<Appeal>>
  /** What applying the template would do right now; changes nothing. */
  previewApply(owner: string, templateKey: string): Promise<ApiResult<ApplyPreview>>
  applyTemplate(owner: string, templateKey: string): Promise<ApiResult<ApplyResult>>
  revoke(owner: string, section: string): Promise<ApiResult<RevokeResult>>
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
  /** Where the browser loads the stored file of an item from; a plain URL because `<img>` and `<video>` cannot send headers. */
  evidenceContentUrl(identifier: string): string
  /** Uploads one file to the punishment; resolves through {@link Upload.result}, can be cancelled. */
  startEvidenceUpload(punishment: string, file: File, onProgress?: (fraction: number) => void): Upload
}

const EVIDENCE_PAGE_SIZE = 20

const PAGE_SIZE = 200
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

/**
 * Query string of the evidence list. `includeInactive` is only sent when true so the default request
 * is the plain one; `source` attributes the (audited) read to the operator.
 */
function evidenceQuery(query: { includeInactive?: boolean; page?: number; size?: number }, source: string): () => string {
  return () => {
    const params = new URLSearchParams({ page: String(query.page ?? 0), size: String(query.size ?? EVIDENCE_PAGE_SIZE), source })
    if (query.includeInactive) params.set('includeInactive', 'true')
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
    steps: draft.steps.map((step) => ({ duration: step.duration.trim(), eloCost: step.eloCost })),
  }
}

/** Server patterns only exist for SERVER sections; a blank soft threshold means the section has none. */
function sectionBody(draft: SectionDraft) {
  const hasSoft = draft.softThreshold !== null
  return {
    key: draft.key.trim(),
    displayName: draft.displayName.trim(),
    enforcement: draft.enforcement,
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
  const http = createHttp({ baseUrl: options.baseUrl, fetch: options.fetch })

  return {
    dashboard: () => toResult(http.GET('/dashboard/summary')),

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

    async reports() {
      const reports = await collectPages<Report>((page) =>
        toResult(http.GET('/report', { params: { query: { pageable: NO_PAGEABLE } }, querySerializer: pageQuery(page) })),
      )
      return map(reports, (all) => [...all].sort((a, b) => b.createdAt - a.createdAt))
    },

    resolveReport: (identifier, resolution) =>
      toResult(
        http.POST('/report/{identifier}/resolve', {
          params: { path: { identifier } },
          body: {
            status: resolution.status,
            resolutionNote: resolution.note ? withActor(resolution.note, options.actingAs?.() ?? '') : null,
            resolvedBy: options.source,
            punishmentTemplateId: resolution.templateId ?? null,
            // The backend wants the staff identity next to the template it applies.
            punishmentSource: resolution.templateId ? options.source : null,
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
            decisionNote: decision.note ? withActor(decision.note, options.actingAs?.() ?? '') : null,
            reviewerId: options.reviewer ?? options.source,
            newExpirationAt: decision.newExpirationAt ?? null,
          },
        }),
      ),

    previewApply: (owner, templateKey) =>
      toResult(http.POST('/punishment/preview', { body: { owner, templateKey, source: options.source } })),

    applyTemplate: (owner, templateKey) =>
      toResult(http.POST('/punishment/apply', { body: { owner, templateKey, source: options.source } })),

    revoke: (owner, section) =>
      toResult(http.POST('/punishment/revoke', { body: { owner, section, source: options.source } })),

    adjustElo: (owner, change) =>
      toResult(
        http.POST('/elo/{owner}/adjust', {
          params: { path: { owner } },
          body: {
            section: change.section,
            delta: change.delta,
            note: withActor(change.note, options.actingAs?.() ?? ''),
            source: options.source,
          },
        }),
      ),

    async evidence(punishment, query = {}) {
      const result = await toResult(
        http.GET('/punishment/{reference}/evidence', {
          params: { path: { reference: punishment }, query: { includeInactive: false, pageable: NO_PAGEABLE } },
          querySerializer: evidenceQuery(query, options.source),
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
      toResult(http.GET('/evidence/{identifier}', { params: { path: { identifier }, query: { source: options.source } } })),

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
            source: options.source,
          },
        }),
      ),

    async deleteEvidence(identifier, change) {
      const result = await toResult(
        http.DELETE('/evidence/{identifier}', {
          params: {
            path: { identifier },
            query: { reason: change.reason, note: change.note?.trim() || null, source: options.source },
          },
        }),
      )
      return map(result, (body) => body.deleted)
    },

    startEvidenceUpload: (punishment, file, onProgress) =>
      uploadEvidence({ baseUrl: options.baseUrl, source: options.source, punishment, file, onProgress }),

    evidenceContentUrl: (identifier) =>
      `${options.baseUrl}/evidence/${encodeURIComponent(identifier)}/content?${new URLSearchParams({ source: options.source })}`,
  }
}
