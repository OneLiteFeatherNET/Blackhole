import { describe, expect, it } from 'vitest'
import { MAX_FILE_BYTES } from '../api/contract'
import { extensionOf, formatSize, precheck } from './files'

const file = (name: string, size: number, type: string) => ({ name, size, type })

describe('precheck', () => {
  it.each([
    ['shot.png', 'image/png'],
    ['photo.jpg', 'image/jpeg'],
    ['photo.jpeg', 'image/jpeg'],
    ['card.webp', 'image/webp'],
    ['appeal.pdf', 'application/pdf'],
    ['notes.txt', 'text/plain'],
  ])('lets %s (%s) through', (name, type) => {
    expect(precheck(file(name, 10, type), 0)).toBeNull()
  })

  it('lets a file with no type through by its extension', () => {
    expect(precheck(file('notes.TXT', 10, ''), 0)).toBeNull()
  })

  it.each([
    ['page.html', 'text/html'],
    ['vector.svg', 'image/svg+xml'],
    ['script.js', 'application/javascript'],
    ['archive.zip', 'application/zip'],
    ['noextension', ''],
    ['unknown.bin', ''],
  ])('refuses %s (%s)', (name, type) => {
    expect(precheck(file(name, 10, type), 0)).toBe('UNSUPPORTED_TYPE')
  })

  it('accepts exactly 10 MiB and refuses one byte more', () => {
    expect(precheck(file('a.png', MAX_FILE_BYTES, 'image/png'), 0)).toBeNull()
    expect(precheck(file('a.png', MAX_FILE_BYTES + 1, 'image/png'), 0)).toBe('TOO_LARGE')
  })

  it('refuses a sixth file in one message', () => {
    expect(precheck(file('a.png', 10, 'image/png'), 4)).toBeNull()
    expect(precheck(file('a.png', 10, 'image/png'), 5)).toBe('TOO_MANY')
  })

  it('reads the extension after the last dot only', () => {
    expect(extensionOf('archive.tar.gz')).toBe('gz')
    expect(extensionOf('noextension')).toBe('')
  })
})

describe('formatSize', () => {
  it.each([
    [0, '0 B'],
    [1023, '1023 B'],
    [1024, '1 KB'],
    [5 * 1024 * 1024, '5.0 MB'],
  ])('shows %i bytes as %s', (bytes, label) => {
    expect(formatSize(bytes)).toBe(label)
  })
})
