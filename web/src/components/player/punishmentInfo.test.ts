import { describe, expect, it } from 'vitest'
import type { Punishment } from '../../api/types'
import { expiresAt, isAutomatic, reasonOf, stepLabel } from './punishmentInfo'

function punishment(overrides: Partial<Punishment> = {}): Punishment {
  return {
    identifier: 'p-1',
    source: '11111111-2222-3333-4444-555555555555',
    section: 'network',
    permanent: false,
    lifted: false,
    metaData: {},
    ...overrides,
  }
}

describe('punishmentInfo', () => {
  it('reads the expiration of a temporary punishment and none for a permanent one', () => {
    const temporary = punishment({ metaData: { expiration_date: 1_900_000_000_000 } as never })

    expect(expiresAt(temporary)).toBe(1_900_000_000_000)
    expect(expiresAt(punishment({ permanent: true }))).toBeNull()
  })

  it('prefers the template reason and falls back to the automatic reason', () => {
    const staff = punishment({ template: { reason: 'Hacking' } as never, metaData: { reason: 'ignored' } as never })
    const automatic = punishment({ metaData: { reason: 'Banned by score', automatic: true } as never })

    expect(reasonOf(staff)).toBe('Hacking')
    expect(reasonOf(automatic)).toBe('Banned by score')
    expect(reasonOf(punishment())).toBe('')
    expect(isAutomatic(automatic)).toBe(true)
    expect(isAutomatic(staff)).toBe(false)
  })

  it('labels the escalation step one-based', () => {
    expect(stepLabel(punishment({ stepIndex: 1, stepCount: 3 }))).toBe('2 / 3')
    expect(stepLabel(punishment({ stepIndex: 0, stepCount: 1 }))).toBe('1 / 1')
    expect(stepLabel(punishment())).toBeNull()
  })
})
