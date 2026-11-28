import { describe, expect, it, vi } from 'vitest'
import type { Component } from 'vue'
import { renderers, resolveRenderer, type RendererLoader } from './registry'

describe('integration renderer registry', () => {
  it('resolves an unknown key to no component, so the generic table is used', () => {
    expect(resolveRenderer('nobody/NOTHING@9')).toBeNull()
  })

  it('resolves a registered key to its loader without calling it', () => {
    const loader = vi.fn<RendererLoader>(() => Promise.resolve({ default: {} as Component }))
    const table: Record<string, RendererLoader> = { 'vulcan/DETECTION@1': loader }

    const resolved = resolveRenderer('vulcan/DETECTION@1', table)

    expect(resolved).toBe(loader)
    expect(loader, 'a lookup must not load the chunk').not.toHaveBeenCalled()
  })

  it('does not resolve inherited object properties as renderers', () => {
    expect(resolveRenderer('constructor', {})).toBeNull()
    expect(resolveRenderer('__proto__', {})).toBeNull()
  })

  it('ships with no entries until a component is reviewed, so every kind uses the generic table', () => {
    expect(Object.keys(renderers)).toEqual([])
  })
})
