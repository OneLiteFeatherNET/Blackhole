import { describe, expect, it } from 'vitest'
import type { RouteRecordRaw } from 'vue-router'
import { routes } from './index'

// Words that must never be a path parameter: a secret, a Ban ID, a player name, a raw UUID, an IP, a Discord id or a token.
const FORBIDDEN_WORDS = new Set(['ban', 'name', 'uuid', 'ip', 'discord', 'token', 'secret'])

/** The parameter names of a path, e.g. `/cases/:reference/notes/:noteId` gives `reference` and `noteId`. */
function pathParams(path: string): string[] {
  return [...path.matchAll(/:([A-Za-z_]\w*)/g)].map((match) => match[1]!)
}

/** The words of a parameter name, split at camel case: `banId` gives `ban` and `id`. */
function words(name: string): string[] {
  return name.replace(/([a-z0-9])([A-Z])/g, '$1 $2').toLowerCase().split(/[\s_-]+/)
}

function forbiddenParams(path: string): string[] {
  return pathParams(path).filter((param) => words(param).some((word) => FORBIDDEN_WORDS.has(word)))
}

function allPaths(list: RouteRecordRaw[], parent = ''): string[] {
  return list.flatMap((route) => {
    const full = `${parent}/${route.path.replace(/^\//, '')}`.replace(/\/+/g, '/')
    return [full, ...allPaths(route.children ?? [], full)]
  })
}

describe('the forbidden parameter check', () => {
  it.each(['banId', 'name', 'uuid', 'ip', 'discordId', 'token', 'secret', 'playerName', 'minecraftUuid'])('flags a parameter named %s', (param) => {
    expect(forbiddenParams(`/x/:${param}`)).toEqual([param])
  })

  it.each(['owner', 'reference', 'shortId', 'auditId', 'pathMatch', 'classification', 'tab'])('accepts a parameter named %s', (param) => {
    expect(forbiddenParams(`/x/:${param}`)).toEqual([])
  })
})

describe('the panel route table', () => {
  it('has no route parameter named like a secret, Ban ID, name, raw UUID, IP, Discord id or token', () => {
    const offending = allPaths(routes).flatMap((path) => forbiddenParams(path).map((param) => `${path} :${param}`))

    expect(offending, 'a route must not take a secret or a personal identifier as a path value').toEqual([])
  })
})
