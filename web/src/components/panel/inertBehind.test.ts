import { afterEach, describe, expect, it } from 'vitest'
import { inertBehind } from './inertBehind'

describe('inertBehind', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('makes the elements beside the sheet and its ancestors inert, but not the sheet', () => {
    const page = document.createElement('main')
    const app = document.createElement('div')
    const sheet = document.createElement('div')
    app.append(page, sheet)
    document.body.append(app)

    inertBehind(sheet)

    expect(page.hasAttribute('inert'), 'the page is inert').toBe(true)
    expect(sheet.hasAttribute('inert'), 'the sheet is not inert').toBe(false)
  })

  it('makes an element outside the app root inert too', () => {
    const outside = document.createElement('aside')
    const app = document.createElement('div')
    const sheet = document.createElement('div')
    app.append(sheet)
    document.body.append(outside, app)

    inertBehind(sheet)

    expect(outside.hasAttribute('inert')).toBe(true)
  })

  it('restores exactly the elements it made inert when released', () => {
    const page = document.createElement('main')
    const sheet = document.createElement('div')
    document.body.append(page, sheet)

    const release = inertBehind(sheet)
    release()

    expect(page.hasAttribute('inert')).toBe(false)
  })

  it('leaves an element that was already inert before the sheet opened as it was', () => {
    const page = document.createElement('main')
    page.setAttribute('inert', '')
    const sheet = document.createElement('div')
    document.body.append(page, sheet)

    inertBehind(sheet)()

    expect(page.hasAttribute('inert'), 'set by someone else, so left alone').toBe(true)
  })
})
