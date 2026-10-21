import { createHttp, map, toResult } from './http'
import type { components } from './schema'
import type {
  ApiResult,
  ApplyPreview,
  ApplyResult,
  DashboardSummary,
  EloAdjustResult,
  EloEvent,
  EloProfile,
  PunishmentProfile,
  ResolvedPlayer,
  RevokeResult,
  Section,
  Template,
  TemplateDraft,
} from './types'

export interface ApiOptions {
  baseUrl: string
  /** The operator UUID from /config.json, attached to every write as the acting source. */
  source: string
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
  /** What applying the template would do right now; changes nothing. */
  previewApply(owner: string, templateKey: string): Promise<ApiResult<ApplyPreview>>
  applyTemplate(owner: string, templateKey: string): Promise<ApiResult<ApplyResult>>
  revoke(owner: string, section: string): Promise<ApiResult<RevokeResult>>
  adjustElo(owner: string, change: { section: string; delta: number; note: string }): Promise<ApiResult<EloAdjustResult>>
}

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

    eloProfile: (owner) => toResult(http.GET('/elo/{owner}', { params: { path: { owner } } })),

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

    templates: () =>
      collectPages<Template>((page) =>
        toResult(http.GET('/template', { params: { query: { pageable: NO_PAGEABLE } }, querySerializer: pageQuery(page) })),
      ),

    createTemplate: (draft) => toResult(http.POST('/template', { body: templateBody(draft) })),

    updateTemplate: (identifier, draft) =>
      toResult(http.POST('/template/update', { body: { identifier, ...templateBody(draft) } })),

    deleteTemplate: (identifier) =>
      toResult(http.DELETE('/template/delete/{identifier}', { params: { path: { identifier } } })),

    sections: () => toResult(http.GET('/section')),

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
  }
}
