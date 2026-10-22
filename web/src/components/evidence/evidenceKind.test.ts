import { describe, expect, it } from 'vitest'
import de from '../../locales/de.json'
import en from '../../locales/en.json'
import { KIND_INFO, kindInfo } from './evidenceKind'

const KINDS = ['ANTICHEAT', 'REPLAY', 'CHAT_LOG', 'LINK', 'FILE'] as const

function lookup(messages: unknown, key: string): unknown {
  return key.split('.').reduce<unknown>((node, part) => (node as Record<string, unknown> | undefined)?.[part], messages)
}

describe('evidence kinds', () => {
  it('maps every backend kind to an icon', () => {
    expect(Object.keys(KIND_INFO).sort()).toEqual([...KINDS].sort())
    for (const kind of KINDS) expect(kindInfo(kind).icon, kind).not.toBe('')
  })

  it('uses a different icon for every kind', () => {
    const icons = KINDS.map((kind) => kindInfo(kind).icon)

    expect(new Set(icons).size).toBe(KINDS.length)
  })

  it.each([
    ['en', en],
    ['de', de],
  ])('has a %s label for every kind', (_language, messages) => {
    for (const kind of KINDS) expect(typeof lookup(messages, kindInfo(kind).labelKey), kind).toBe('string')
  })
})
