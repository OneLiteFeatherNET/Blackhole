import { describe, expect, it } from 'vitest'
import { LIST_QUERY, OWNER, PAGE, choice, cleanListQuery, pickParams, readListQuery, renameLegacyQuery, text, writeListQuery } from './listQuery'

const PARAMS = { status: choice(['open', 'all']), page: text(PAGE) }
const OWNER_HASH = 'ab'.repeat(32)

describe('readListQuery', () => {
  it('reads the default, the empty value, for a missing choice and a missing text', () => {
    expect(readListQuery({}, PARAMS)).toEqual({ status: '', page: '' })
  })

  it('reads an allowed choice and a matching text', () => {
    expect(readListQuery({ status: 'all', page: '2' }, PARAMS)).toEqual({ status: 'all', page: '2' })
  })

  it.each([
    ['an unknown choice', { status: 'closed' }],
    ['a repeated key', { status: ['all', 'open'] }],
    ['a text that does not match', { page: '0' }],
    ['a text with letters', { page: 'two' }],
    ['an empty text', { page: '' }],
  ])('reads the default for %s', (_label, query) => {
    expect(readListQuery(query, PARAMS)).toEqual({ status: '', page: '' })
  })

  it('ignores keys that are not parameters of the list', () => {
    expect(readListQuery({ x: '1' }, PARAMS)).toEqual({ status: '', page: '' })
  })
})

describe('writeListQuery', () => {
  it('writes a value and leaves out the default', () => {
    expect(writeListQuery({}, { status: 'all', page: '' }, PARAMS)).toEqual({ status: 'all' })
    expect(writeListQuery({ status: 'all' }, { status: '', page: '' }, PARAMS)).toEqual({})
  })

  it('keeps the keys that are not parameters of the list', () => {
    expect(writeListQuery({ x: '1', status: 'all' }, { status: '', page: '3' }, PARAMS)).toEqual({ x: '1', page: '3' })
  })

  it('does not write a value the list does not allow', () => {
    expect(writeListQuery({}, { status: 'closed', page: 'x' }, PARAMS)).toEqual({})
  })

  it('reads back what it wrote', () => {
    const query = writeListQuery({}, { status: 'all', page: '2' }, PARAMS)

    expect(readListQuery(query, PARAMS)).toEqual({ status: 'all', page: '2' })
  })
})

describe('cleanListQuery', () => {
  it('returns nothing when every value of the list is valid or absent', () => {
    expect(cleanListQuery({ status: 'all', x: '1' }, PARAMS)).toBeUndefined()
    expect(cleanListQuery({ x: 'nope' }, PARAMS)).toBeUndefined()
  })

  it('removes the invalid values and keeps the valid ones and the other keys', () => {
    expect(cleanListQuery({ status: 'closed', page: '0', x: '1' }, PARAMS)).toEqual({ x: '1' })
    expect(cleanListQuery({ status: 'all', page: '4' }, PARAMS)).toBeUndefined()
  })

  it('removes a repeated key, which is not a value', () => {
    expect(cleanListQuery({ status: ['all', 'open'] }, PARAMS)).toEqual({})
  })
})

describe('pickParams', () => {
  it('keeps the named parameters only', () => {
    expect(Object.keys(pickParams(PARAMS, ['page']))).toEqual(['page'])
  })
})

describe('renameLegacyQuery', () => {
  const renames = [{ from: 'show', to: 'status', values: { all: 'all' } }]

  it('moves an old value that is still known to the new name, in place, and keeps the other keys', () => {
    expect(renameLegacyQuery({ x: '1', show: 'all', q: 'y' }, renames)).toEqual({ x: '1', status: 'all', q: 'y' })
  })

  it('drops an old value that has no new spelling', () => {
    expect(renameLegacyQuery({ show: 'pending' }, renames)).toEqual({})
  })

  it('keeps the new name when the address has both', () => {
    expect(renameLegacyQuery({ show: 'all', status: 'open' }, renames)).toEqual({ status: 'open' })
  })

  it('returns nothing when the old name is not used', () => {
    expect(renameLegacyQuery({ status: 'all' }, renames)).toBeUndefined()
  })
})

describe('the parameters of each list page', () => {
  it.each(Object.entries(LIST_QUERY))('%s reads the default for an empty query', (_route, params) => {
    const values = readListQuery({}, params)

    expect(Object.values(values)).toEqual(Object.keys(params).map(() => ''))
  })

  it.each(Object.entries(LIST_QUERY))('%s removes every invalid value', (_route, params) => {
    const invalid = Object.fromEntries(Object.keys(params).map((key) => [key, '\u0001 invalid value with spaces and ;']))

    expect(cleanListQuery(invalid, params)).toEqual({})
  })

  it('takes the owner hash for a report filter and refuses a name', () => {
    expect(readListQuery({ player: OWNER_HASH }, LIST_QUERY.reports!).player).toBe(OWNER_HASH)
    expect(readListQuery({ player: 'Steve' }, LIST_QUERY.reports!).player).toBe('')
    expect(OWNER.test('Steve')).toBe(false)
  })
})
