import { createHttp, map, toResult } from './http'
import type { components } from './schema'
import type {
  ApiResult,
  DashboardSummary,
  EloEvent,
  EloProfile,
  PunishmentProfile,
  ResolvedPlayer,
} from './types'

export interface ApiOptions {
  baseUrl: string
  /** The operator UUID from /config.json, attached to every write as the acting source. */
  source: string
  fetch?: typeof fetch
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
  }
}
