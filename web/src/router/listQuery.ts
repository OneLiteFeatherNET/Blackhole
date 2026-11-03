import type { LocationQueryRaw } from 'vue-router'

/**
 * The state of a list in its address (`restructure-staff-panel`, decision 3 and staff-deep-links): filters, sort and
 * page are query parameters, never storage. A value is one allowed choice or one text that matches its pattern.
 * `''` is the default of every parameter: it is not written, and the page shows its default view for it.
 */

export type ListParam = { kind: 'choice'; allowed: readonly string[] } | { kind: 'text'; pattern: RegExp }

export type ListParams = Readonly<Record<string, ListParam>>
export type ListValues = Record<string, string>

/** A choice among the allowed values. */
export function choice(allowed: readonly string[]): ListParam {
  return { kind: 'choice', allowed }
}

/** A text value that must match `pattern`. An empty string is the value that is not applied. */
export function text(pattern: RegExp): ListParam {
  return { kind: 'text', pattern }
}

/** A page as a query value: `''` is the first page, `1` the second. */
export const PAGE = /^[1-9]\d{0,5}$/
/** A player's owner: the SHA-512 hex of the account (128 characters), never the name or the raw UUID. As in the backend. */
export const OWNER = /^[0-9a-fA-F]{128}$/
/** A search text: printable, up to 64 characters. */
export const SEARCH = /^[^\u0000-\u001f\u007f]{1,64}$/

function isValid(param: ListParam, value: string): boolean {
  return param.kind === 'choice' ? param.allowed.includes(value) : param.pattern.test(value)
}

/** The value of a query key as one string: a repeated key is not a value. */
function single(raw: unknown): string | undefined {
  return typeof raw === 'string' ? raw : undefined
}

/** The parameters of a list as values. A missing or invalid value reads as `''`, the default. */
export function readListQuery(query: LocationQueryRaw, params: ListParams): ListValues {
  return Object.fromEntries(
    Object.entries(params).map(([key, param]) => {
      const raw = single(query[key])
      return [key, raw !== undefined && isValid(param, raw) ? raw : '']
    }),
  )
}

/**
 * The query with the list's parameters set to `values`. Every parameter of the list is set from `values`, so pass all
 * of them. Unrelated keys are kept. `''` is left out, so the default address stays short, and a value the list does
 * not allow is not written.
 */
export function writeListQuery(query: LocationQueryRaw, values: ListValues, params: ListParams): LocationQueryRaw {
  const next: LocationQueryRaw = { ...query }
  for (const [key, param] of Object.entries(params)) {
    delete next[key]
    const value = values[key] ?? ''
    if (value !== '' && isValid(param, value)) next[key] = value
  }
  return next
}

/** The parameters named in `keys`, from a list's parameters. */
export function pickParams(params: ListParams, keys: readonly string[]): ListParams {
  return Object.fromEntries(keys.map((key) => [key, params[key]!])) as ListParams
}

/**
 * The query without the invalid values of the list's parameters, or `undefined` when there is none. Only invalid
 * values are removed; a valid default stays, so the caller replaces the address only when something was removed.
 */
export function cleanListQuery(query: LocationQueryRaw, params: ListParams): LocationQueryRaw | undefined {
  let cleaned: LocationQueryRaw | undefined
  for (const [key, param] of Object.entries(params)) {
    if (query[key] === undefined) continue
    const raw = single(query[key])
    if (raw !== undefined && isValid(param, raw)) continue
    cleaned ??= { ...query }
    delete cleaned[key]
  }
  return cleaned
}

/** An old parameter name that moved: its value carries over under the new name when the value is still known. */
export interface LegacyRename {
  from: string
  to: string
  /** The old values that carry over, with their new spelling. Any other old value is dropped. */
  values: Readonly<Record<string, string>>
}

/** The query with old parameter names moved to their new names, in place. Unrelated keys are kept. */
export function renameLegacyQuery(query: LocationQueryRaw, renames: readonly LegacyRename[]): LocationQueryRaw | undefined {
  if (!renames.some((rename) => query[rename.from] !== undefined)) return undefined
  const next: LocationQueryRaw = {}
  for (const [key, value] of Object.entries(query)) {
    const rename = renames.find((candidate) => candidate.from === key)
    if (!rename) {
      next[key] = value
      continue
    }
    const old = single(value)
    const mapped = old !== undefined && Object.hasOwn(rename.values, old) ? rename.values[old] : undefined
    // The new name wins when the address has both; an old value without a new spelling is dropped.
    if (mapped !== undefined && query[rename.to] === undefined) next[rename.to] = mapped
  }
  return next
}

const CASE_STATUS = ['OPEN', 'IN_REVIEW', 'WAITING', 'CLOSED'] as const
/** A case tag: lower case letters, digits and dashes. */
export const TAG = /^[a-z0-9-]{1,32}$/
const SORTS = ['created-desc', 'created-asc', 'expires-desc', 'expires-asc'] as const

const CASES: ListParams = {
  status: choice(CASE_STATUS),
  mine: choice(['1']),
  tag: text(TAG),
  view: choice(['cases', 'player']),
  page: text(PAGE),
}

const REPORTS: ListParams = { status: choice(['open', 'resolved', 'all']), player: text(OWNER) }

/**
 * The query parameters of each list, by route name. A detail page that shows one item of a list (a case, a report)
 * keeps the list's parameters, so Back returns to the same list.
 */
export const LIST_QUERY: Readonly<Record<string, ListParams>> = {
  cases: CASES,
  // A detail page of a list keeps the list's parameters, so Back returns to the same list.
  case: CASES,
  appeals: { status: choice(['pending', 'all']) },
  reports: REPORTS,
  report: REPORTS,
  punished: {
    kind: choice(['BAN', 'MUTE', 'SERVER']),
    section: text(/^[a-z0-9][a-z0-9_-]{0,63}$/i),
    term: choice(['permanent', 'temporary', 'soon']),
    source: choice(['automatic', 'manual']),
    mine: choice(['1']),
    sort: choice(SORTS),
    page: text(PAGE),
  },
  'ip-bans': { status: choice(['active', 'lifted']) },
  'area-bans': { status: choice(['active', 'lifted']) },
  'intel-allow-list': { page: text(PAGE) },
  'admin-templates': { q: text(SEARCH) },
  'admin-sections': { q: text(SEARCH) },
  'admin-client-rules': { q: text(SEARCH) },
  'admin-staff': { q: text(SEARCH) },
  'admin-exemption-reviews': { status: choice(['open', 'decided']) },
  'admin-audit': {
    action: text(/^[a-z-]+\.[a-z-]+$/),
    actor: text(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/),
    target: text(/^[A-Za-z0-9_-]{1,64}$/),
    cursor: text(/^[A-Za-z0-9+/=_.:~-]{1,512}$/),
  },
}

/**
 * Parameters that are no longer part of a list's address, by route name; a link that still has one loses it. The
 * punished list's search `q` matches player names, and a name must never be in an address (GDPR, design decision 8).
 */
export const RETIRED_LIST_QUERY: Readonly<Record<string, readonly string[]>> = {
  punished: ['q'],
}

/** Old list parameter names, by route name. */
export const LEGACY_LIST_QUERY: Readonly<Record<string, readonly LegacyRename[]>> = {
  appeals: [{ from: 'show', to: 'status', values: { all: 'all' } }],
  reports: [{ from: 'show', to: 'status', values: { all: 'all' } }],
}
