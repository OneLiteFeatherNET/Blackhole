import { describe, expect, it } from 'vitest'
import { formatPanelParam, parsePanelParam, setPanelQuery } from './panelState'

describe('parsePanelParam', () => {
  it.each([
    ['client-rule:new', [{ kind: 'client-rule', target: 'new' }]],
    ['evidence:CASE-12,link:new', [{ kind: 'evidence', target: 'CASE-12' }, { kind: 'link', target: 'new' }]],
    ['client-rule:brand.block_1', [{ kind: 'client-rule', target: 'brand.block_1' }]],
  ])('reads the tokens of %s in order', (value, expected) => {
    expect(parsePanelParam(value)).toEqual(expected)
  })

  it('drops a third token and keeps the first two', () => {
    expect(parsePanelParam('evidence:CASE-12,link:new,client-rule:new')).toEqual([
      { kind: 'evidence', target: 'CASE-12' },
      { kind: 'link', target: 'new' },
    ])
  })

  it('drops a token whose kind is not registered and keeps the others', () => {
    expect(parsePanelParam('evidence:CASE-12,unknown:new')).toEqual([{ kind: 'evidence', target: 'CASE-12' }])
  })

  it('drops a token whose target is not a key or new', () => {
    expect(parsePanelParam('client-rule:bad target')).toEqual([])
  })

  it.each([undefined, null, ''])('reads %p as no tokens', (value) => {
    expect(parsePanelParam(value)).toEqual([])
  })
})

describe('formatPanelParam', () => {
  it('writes tokens as kind:target joined by commas', () => {
    expect(formatPanelParam([{ kind: 'evidence', target: 'CASE-12' }, { kind: 'link', target: 'new' }])).toBe(
      'evidence:CASE-12,link:new',
    )
  })

  it.each(['client-rule:new', 'evidence:CASE-12,link:new'])('round-trips %s unchanged', (value) => {
    expect(formatPanelParam(parsePanelParam(value))).toBe(value)
  })
})

describe('setPanelQuery', () => {
  it('keeps the other query parameters, including filters', () => {
    expect(setPanelQuery({ status: 'open', page: '2' }, [{ kind: 'client-rule', target: 'new' }])).toEqual({
      status: 'open',
      page: '2',
      panel: 'client-rule:new',
    })
  })

  it('removes the panel parameter when no sheet is open and keeps the others', () => {
    expect(setPanelQuery({ status: 'open', panel: 'client-rule:new' }, [])).toEqual({ status: 'open' })
  })

  it('does not change the query it was given', () => {
    const query = { panel: 'client-rule:new' }
    setPanelQuery(query, [])
    expect(query).toEqual({ panel: 'client-rule:new' })
  })
})
