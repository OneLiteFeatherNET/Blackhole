import { describe, expect, it } from 'vitest'
import { guardSource } from './guardState'

describe('guardSource', () => {
  it('names the manual setting when it is the level in force', () => {
    expect(guardSource({ manualLevel: 'STRICT', effectiveLevel: 'STRICT' })).toBe('manual')
  })

  it('names the unattended evaluator when it is stricter than the manual level', () => {
    expect(guardSource({ manualLevel: 'OFF', effectiveLevel: 'STRICT' })).toBe('unattended')
  })

  it('names the manual setting when both levels agree', () => {
    expect(guardSource({ manualLevel: 'LOCKDOWN', effectiveLevel: 'LOCKDOWN' })).toBe('manual')
  })

  it('reports cleared when no level is in force', () => {
    expect(guardSource({ manualLevel: 'OFF', effectiveLevel: 'OFF' })).toBe('cleared')
  })
})
