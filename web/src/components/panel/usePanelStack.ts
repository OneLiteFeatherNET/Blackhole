import { computed, watch, type ComputedRef } from 'vue'
import type { Router } from 'vue-router'
import { formatPanelParam, parsePanelParam, PANEL_KINDS, setPanelQuery, type PanelToken } from './panelState'

export interface PanelStackOptions {
  router: Router
  /** Synchronous check of the role; a token it refuses is removed from the URL, without any request. */
  allowed?: (token: PanelToken) => boolean
  known?: ReadonlySet<string>
}

export interface PanelStack {
  /** The open sheets, parent first. */
  tokens: ComputedRef<PanelToken[]>
  /** Opens a sheet on top. Pushes a history entry. Refused (returns without navigating) at depth two. */
  open: (token: PanelToken) => Promise<void>
  /** Closes the top sheet. Replaces the history entry. */
  close: () => Promise<void>
}

const MAX_DEPTH = 2

/** Keeps the stack of sheets in the `panel` query parameter of the current route. */
export function usePanelStack(options: PanelStackOptions): PanelStack {
  const { router } = options
  const known = options.known ?? PANEL_KINDS
  const allowed = options.allowed ?? (() => true)

  const tokens = computed<PanelToken[]>(() =>
    parsePanelParam(queryValue(router.currentRoute.value.query.panel), known).filter(allowed),
  )

  // Whatever the URL names but the stack does not keep (unknown, forbidden, a third token) is rewritten away.
  // Runs at once and on every navigation, so a shared link never shows a sheet the user may not see.
  watch(
    () => router.currentRoute.value,
    (route) => {
      const raw = queryValue(route.query.panel)
      if (raw === undefined && !route.query.panel) return
      const clean = formatPanelParam(tokens.value)
      if (clean === raw) return
      void router.replace({ query: setPanelQuery(route.query, tokens.value) })
    },
    { immediate: true },
  )

  return {
    tokens,
    open: (token) => {
      const current = tokens.value
      if (current.length >= MAX_DEPTH) return Promise.resolve()
      return router.push({ query: setPanelQuery(router.currentRoute.value.query, [...current, token]) }).then(() => undefined)
    },
    close: () => {
      const current = tokens.value
      if (current.length === 0) return Promise.resolve()
      return router.replace({ query: setPanelQuery(router.currentRoute.value.query, current.slice(0, -1)) }).then(() => undefined)
    },
  }
}

function queryValue(value: unknown): string | undefined {
  if (Array.isArray(value)) return typeof value[0] === 'string' ? value[0] : undefined
  return typeof value === 'string' ? value : undefined
}
