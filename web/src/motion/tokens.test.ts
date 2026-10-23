import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const css = readFileSync(join(import.meta.dirname, '../styles/main.css'), 'utf8')

const sources = import.meta.glob('../**/*.vue', { query: '?raw', import: 'default', eager: true }) as Record<string, string>

const EASINGS = ['standard', 'emphasized', 'emphasized-decelerate', 'emphasized-accelerate']
const DURATIONS = ['short1', 'short2', 'short3', 'short4', 'medium1', 'medium2', 'medium3', 'medium4', 'long1', 'long2']

/** The `@media (prefers-reduced-motion: reduce)` block that holds the token overrides. */
function reducedBlock(): string {
  const start = css.indexOf('@media (prefers-reduced-motion: reduce)')
  expect(start, 'a reduced motion block').toBeGreaterThan(-1)
  return css.slice(start, css.indexOf('\n}\n', start))
}

describe('motion tokens', () => {
  it.each(EASINGS)('defines the %s easing as a cubic bezier', (name) => {
    expect(css).toMatch(new RegExp(`--md-sys-motion-easing-${name}:\\s*cubic-bezier\\(`))
  })

  it.each(DURATIONS)('defines the %s duration in milliseconds', (name) => {
    expect(css).toMatch(new RegExp(`--md-sys-motion-duration-${name}:\\s*\\d+ms;`))
  })

  it('takes away the distance, the scale and the stagger under reduced motion, leaving the fade', () => {
    const block = reducedBlock()

    expect(block).toContain('--md-motion-shift: 0px')
    expect(block).toContain('--md-motion-scale: 1')
    expect(block).toContain('--md-motion-stagger: 0ms')
  })

  it('shortens the long durations under reduced motion', () => {
    expect(reducedBlock()).toMatch(/--md-sys-motion-duration-long2:\s*var\(--md-sys-motion-duration-short2\)/)
  })

  it('animates nothing but opacity and transform in the Vue transition classes', () => {
    const rules = css.match(/\.md-[a-z-]+-(?:enter|leave)-active[^{]*\{[^}]*\}/g) ?? []
    expect(rules.length).toBeGreaterThan(0)
    for (const rule of rules) {
      for (const property of rule.matchAll(/transition:\s*([^;]*);/g)) {
        const named = property[1]!.split(',').map((part) => part.trim().split(/\s+/)[0])
        expect(named.every((item) => item === 'opacity' || item === 'transform'), rule).toBe(true)
      }
    }
  })

  it('has no hard coded durations or easings in the components', () => {
    const offenders = Object.entries(sources).filter(([, source]) => /\b(?:duration|delay)-\d|\bease-(?:in|out|linear)|\d(?:ms|s)\s+(?:ease|linear)/.test(source))

    expect(offenders.map(([path]) => path)).toEqual([])
  })
})
