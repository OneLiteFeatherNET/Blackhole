import { createMemoryHistory, type RouteRecordRaw } from 'vue-router'
import { describe, expect, it } from 'vitest'
import { createAppRouter, routes } from './index'
import { RETURN_ROUTE_NAMES, allowedReturnTo } from './returnTo'

function routeNames(list: RouteRecordRaw[]): string[] {
  return list.flatMap((route) => [...(route.name ? [String(route.name)] : []), ...routeNames(route.children ?? [])])
}

describe('allowedReturnTo', () => {
  it('follows an allow-listed route with its query and fragment', () => {
    const router = createAppRouter(createMemoryHistory())

    expect(allowedReturnTo(router, '/cases/A1B2C3D4?tab=conversation')).toBe('/cases/A1B2C3D4?tab=conversation')
    expect(allowedReturnTo(router, '/admin/sections?q=x#top')).toBe('/admin/sections?q=x#top')
  })

  it.each([
    ['the callback route', '/auth/callback'],
    ['the callback route with its parameters', '/auth/callback?code=x&state=y'],
    ['the error page', '/auth/error?reason=x'],
    ['the no-access page', '/no-access'],
    ['the leave interstitial', '/leave#https://evil.example'],
    ['a path that matches only the catch-all', '/does-not-exist'],
  ])('ignores %s and uses the start page', (_label, value) => {
    const router = createAppRouter(createMemoryHistory())

    expect(allowedReturnTo(router, value)).toBe('/')
  })

  it.each(['https://evil.example/cases', '//evil.example', '/\\evil.example', '/%2F%2Fevil.example'])('ignores the unsafe target %s', (value) => {
    const router = createAppRouter(createMemoryHistory())

    expect(allowedReturnTo(router, value)).toBe('/')
  })

  it('ignores a value that is not a string', () => {
    const router = createAppRouter(createMemoryHistory())

    expect(allowedReturnTo(router, { path: '/cases' })).toBe('/')
  })
})

describe('the return route allow-list', () => {
  it('names only routes that exist in the route table', () => {
    const existing = new Set(routeNames(routes))

    expect([...RETURN_ROUTE_NAMES].filter((name) => !existing.has(name))).toEqual([])
  })

  it('leaves out the sign-in, leave, no-access and catch-all routes', () => {
    for (const name of ['auth-callback', 'auth-error', 'leave', 'not-found', 'no-access']) {
      expect(RETURN_ROUTE_NAMES.has(name), name).toBe(false)
    }
  })
})
