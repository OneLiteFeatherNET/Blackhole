import { createHttp, toResult } from './http'
import type { ApiResult, DashboardSummary } from './types'

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
}

export function createBlackholeApi(options: ApiOptions): BlackholeApi {
  const http = createHttp({ baseUrl: options.baseUrl, fetch: options.fetch })

  return {
    dashboard: () => toResult(http.GET('/dashboard/summary')),
  }
}
