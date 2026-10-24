import { describe, expect, it } from 'vitest'
import { parseConditions } from './conditions'

describe('parseConditions', () => {
  it('splits paragraphs at blank lines and joins their wrapped lines', () => {
    expect(parseConditions('first line\nsecond line\n\nnext paragraph')).toEqual([
      { kind: 'paragraph', text: 'first line second line' },
      { kind: 'paragraph', text: 'next paragraph' },
    ])
  })

  it('collects consecutive dash lines into one list', () => {
    expect(parseConditions('- one\n- two\n- three')).toEqual([{ kind: 'list', items: ['one', 'two', 'three'] }])
  })

  it('puts a paragraph before and after a list in their own blocks', () => {
    expect(parseConditions('Read this.\n\n- a\n- b\n\nThanks.')).toEqual([
      { kind: 'paragraph', text: 'Read this.' },
      { kind: 'list', items: ['a', 'b'] },
      { kind: 'paragraph', text: 'Thanks.' },
    ])
  })

  it('ends a list at a plain line even without a blank line', () => {
    expect(parseConditions('- a\nplain')).toEqual([
      { kind: 'list', items: ['a'] },
      { kind: 'paragraph', text: 'plain' },
    ])
  })

  it('accepts Windows line endings', () => {
    expect(parseConditions('- a\r\n- b')).toEqual([{ kind: 'list', items: ['a', 'b'] }])
  })

  it('keeps markup characters as they are', () => {
    expect(parseConditions('- <b>bold</b>\n\n<script>alert(1)</script> **x**')).toEqual([
      { kind: 'list', items: ['<b>bold</b>'] },
      { kind: 'paragraph', text: '<script>alert(1)</script> **x**' },
    ])
  })

  it('returns nothing for an empty text', () => {
    expect(parseConditions('  \n ')).toEqual([])
  })
})
