import { afterEach, describe, expect, it, vi } from 'vitest'
import { createListKeys, detailIsShown, isTyping, listIsShown, rowDomId, type ListKeyOptions } from './listDetail'

/** Spies for every key action; `overrides` sets the state of the screen. */
function setup(overrides: Partial<ListKeyOptions> = {}) {
  const spies = { next: vi.fn(), previous: vi.fn(), open: vi.fn(), close: vi.fn() }
  const handler = createListKeys({ listShown: () => true, detailOpen: () => true, ...spies, ...overrides })
  document.addEventListener('keydown', handler)
  return { spies, stop: () => document.removeEventListener('keydown', handler) }
}

/** Presses a key on an element and tells whether the handler cancelled it (preventDefault). */
function press(target: HTMLElement, key: string, init: KeyboardEventInit = {}): boolean {
  const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init })
  target.dispatchEvent(event)
  return event.defaultPrevented
}

function element(tag: string, attributes: Record<string, string> = {}): HTMLElement {
  const created = document.createElement(tag)
  for (const [name, value] of Object.entries(attributes)) created.setAttribute(name, value)
  document.body.append(created)
  return created
}

describe('list keys', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('moves the selection down with j and up with k', () => {
    const { spies, stop } = setup()

    expect(press(document.body, 'j')).toBe(true)
    press(document.body, 'k')
    stop()

    expect(spies.next).toHaveBeenCalledTimes(1)
    expect(spies.previous).toHaveBeenCalledTimes(1)
  })

  it('opens the selected row with Enter when the focus is on the row itself', () => {
    const { spies, stop } = setup()
    const row = element('tr', { tabindex: '-1' })

    expect(press(row, 'Enter')).toBe(true)
    stop()

    expect(spies.open).toHaveBeenCalledTimes(1)
  })

  it('leaves Enter to a button, a link or a checkbox that has the focus', () => {
    const { spies, stop } = setup()

    press(element('button'), 'Enter')
    press(element('a', { href: '#x' }), 'Enter')
    press(element('input', { type: 'checkbox' }), 'Enter')
    stop()

    expect(spies.open).not.toHaveBeenCalled()
  })

  it('closes the detail with Escape while a detail is open', () => {
    const { spies, stop } = setup()

    expect(press(document.body, 'Escape')).toBe(true)
    stop()

    expect(spies.close).toHaveBeenCalledTimes(1)
  })

  it('does not close anything with Escape when no detail is open', () => {
    const { spies, stop } = setup({ detailOpen: () => false })

    press(document.body, 'Escape')
    stop()

    expect(spies.close).not.toHaveBeenCalled()
  })

  it('does nothing with the list keys while the list is not on screen', () => {
    const { spies, stop } = setup({ listShown: () => false })

    press(document.body, 'j')
    press(document.body, 'Enter')
    stop()

    expect(spies.next).not.toHaveBeenCalled()
    expect(spies.open).not.toHaveBeenCalled()
  })

  it('ignores the keys while a text field, a text area or a select has the focus', () => {
    const { spies, stop } = setup()

    for (const target of [element('input', { type: 'text' }), element('textarea'), element('select')]) {
      press(target, 'j')
      press(target, 'Enter')
      press(target, 'Escape')
    }
    stop()

    expect(spies.next).not.toHaveBeenCalled()
    expect(spies.open).not.toHaveBeenCalled()
    expect(spies.close).not.toHaveBeenCalled()
  })

  it('keeps the row keys while a checkbox has the focus, since a checkbox is no text field', () => {
    const { spies, stop } = setup()

    press(element('input', { type: 'checkbox' }), 'j')
    stop()

    expect(spies.next).toHaveBeenCalledTimes(1)
  })

  it('ignores the keys while a dialog is open', () => {
    const { spies, stop } = setup()
    element('div', { role: 'dialog' })

    press(document.body, 'j')
    press(document.body, 'Escape')
    stop()

    expect(spies.next).not.toHaveBeenCalled()
    expect(spies.close).not.toHaveBeenCalled()
  })

  it('ignores the keys while a menu is open', () => {
    const { spies, stop } = setup()
    element('div', { role: 'menu' })

    press(document.body, 'k')
    stop()

    expect(spies.previous).not.toHaveBeenCalled()
  })

  it('ignores a key pressed with a modifier', () => {
    const { spies, stop } = setup()

    press(document.body, 'j', { ctrlKey: true })
    press(document.body, 'Enter', { metaKey: true })
    stop()

    expect(spies.next).not.toHaveBeenCalled()
    expect(spies.open).not.toHaveBeenCalled()
  })

  it('does not cancel keys it does not use', () => {
    const { stop } = setup()

    expect(press(document.body, 'x')).toBe(false)
    expect(press(document.body, 'Tab')).toBe(false)
    stop()
  })
})

describe('isTyping', () => {
  it.each([
    ['a text input', 'input', { type: 'text' }, true],
    ['a text area', 'textarea', {}, true],
    ['a select', 'select', {}, true],
    ['a checkbox', 'input', { type: 'checkbox' }, false],
    ['a button', 'button', {}, false],
    ['a row', 'tr', {}, false],
  ])('treats %s as typing: %s', (_, tag, attributes, expected) => {
    expect(isTyping(element(tag, attributes))).toBe(expected)
  })

  it('does not treat a missing target as typing', () => {
    expect(isTyping(null)).toBe(false)
  })
})

describe('layout by width', () => {
  it('shows the list beside the detail at expanded width, with or without an open item', () => {
    expect(listIsShown('expanded', true)).toBe(true)
    expect(listIsShown('expanded', false)).toBe(true)
    expect(detailIsShown('expanded', true)).toBe(true)
    expect(detailIsShown('expanded', false)).toBe(true)
  })

  it('shows the detail in place of the list at medium and compact width once an item is open', () => {
    for (const size of ['medium', 'compact'] as const) {
      expect(listIsShown(size, true), size).toBe(false)
      expect(detailIsShown(size, true), size).toBe(true)
    }
  })

  it('shows only the list at medium and compact width while no item is open', () => {
    for (const size of ['medium', 'compact'] as const) {
      expect(listIsShown(size, false), size).toBe(true)
      expect(detailIsShown(size, false), size).toBe(false)
    }
  })
})

describe('rowDomId', () => {
  it('builds the element id of a row from its kind and key', () => {
    expect(rowDomId('case-row', 'p-0001')).toBe('case-row-p-0001')
  })
})
