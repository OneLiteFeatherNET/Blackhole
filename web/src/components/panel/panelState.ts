// The state of the panel stack, kept in the `panel` query parameter. Pure functions only: no router import,
// so the rules are unit-tested without one. Grammar: `<kind>:<target>`, comma-separated, parent first,
// at most two tokens. `target` is `new`, a key or an id.

export interface PanelToken {
  kind: string
  target: string
}

/** The sheet kinds the panel knows. A component that adds a kind adds it here. */
export const PANEL_KINDS: ReadonlySet<string> = new Set(['client-rule', 'evidence', 'link'])

/** The query of a route, as vue-router holds it (structurally the same as its LocationQuery). */
export type PanelQuery = Record<string, string | null | undefined | (string | null)[]>

const MAX_TOKENS = 2
const KIND = /^[a-z-]{1,32}$/
const TARGET = /^(new|[A-Za-z0-9._-]{1,64})$/

/** Reads the `panel` parameter. Invalid tokens are dropped; a third token is dropped too. */
export function parsePanelParam(value: string | null | undefined, known: ReadonlySet<string> = PANEL_KINDS): PanelToken[] {
  if (!value) return []
  const tokens: PanelToken[] = []
  for (const raw of value.split(',')) {
    const separator = raw.indexOf(':')
    if (separator < 0) continue
    const kind = raw.slice(0, separator)
    const target = raw.slice(separator + 1)
    if (!KIND.test(kind) || !known.has(kind) || !TARGET.test(target)) continue
    tokens.push({ kind, target })
    if (tokens.length === MAX_TOKENS) break
  }
  return tokens
}

/** Writes tokens back to the parameter value. */
export function formatPanelParam(tokens: PanelToken[]): string {
  return tokens.map((token) => `${token.kind}:${token.target}`).join(',')
}

/** Returns a copy of the query with the `panel` parameter set to the tokens, or removed when there are none. */
export function setPanelQuery(
  query: PanelQuery,
  tokens: PanelToken[],
): PanelQuery {
  const next = { ...query }
  const value = formatPanelParam(tokens)
  if (value) next.panel = value
  else delete next.panel
  return next
}
