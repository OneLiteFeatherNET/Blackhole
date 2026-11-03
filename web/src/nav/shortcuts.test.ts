import { afterEach, describe, expect, it, vi } from 'vitest'
import { createShortcuts, isTextEntry } from './shortcuts'

interface Fake {
  key: string
  target?: EventTarget | null
  ctrlKey?: boolean
  metaKey?: boolean
  altKey?: boolean
  shiftKey?: boolean
}

/** A keyboard event as the handler reads it; only the fields it uses are present. */
function press({ key, target = document.body, ctrlKey = false, metaKey = false, altKey = false, shiftKey = false }: Fake) {
  return { key, target, ctrlKey, metaKey, altKey, shiftKey, preventDefault: vi.fn() } as unknown as KeyboardEvent
}

function setup(options: { enabled?: boolean } = {}) {
  const navigate = vi.fn<(to: string) => void>()
  const openHelp = vi.fn<() => void>()
  const handle = createShortcuts({ navigate, openHelp, enabled: () => options.enabled ?? true })
  return { navigate, openHelp, handle }
}

/** A div with the contenteditable attribute; jsdom does not compute isContentEditable, the attribute is what counts. */
function editable(): HTMLElement {
  const element = document.createElement('div')
  element.setAttribute('contenteditable', 'true')
  return element
}

function typeInto(element: HTMLElement): HTMLElement {
  document.body.appendChild(element)
  return element
}

afterEach(() => {
  document.body.innerHTML = ''
})

describe('chords on the top destinations', () => {
  it.each([
    ['i', '/'],
    ['c', '/cases'],
    ['a', '/appeals'],
    ['r', '/reports'],
    ['p', '/players'],
  ])('opens the destination of g %s', (letter, to) => {
    const { navigate, handle } = setup()

    handle(press({ key: 'g' }))
    handle(press({ key: letter }))

    expect(navigate).toHaveBeenCalledExactlyOnceWith(to)
  })

  it('ignores a letter that is not bound after g and forgets the g', () => {
    const { navigate, handle } = setup()

    handle(press({ key: 'g' }))
    handle(press({ key: 'x' }))
    handle(press({ key: 'c' }))

    expect(navigate, 'the c on its own').not.toHaveBeenCalled()
  })

  it('needs the g right before the letter', () => {
    const { navigate, handle } = setup()

    handle(press({ key: 'g' }))
    handle(press({ key: 'g' }))
    handle(press({ key: 'c' }))

    expect(navigate).toHaveBeenCalledExactlyOnceWith('/cases')
  })

  it('does not react to a bare letter', () => {
    const { navigate, handle } = setup()

    handle(press({ key: 'c' }))

    expect(navigate).not.toHaveBeenCalled()
  })

  it('leaves Ctrl, Meta and Alt combinations to the browser', () => {
    const { navigate, handle } = setup()

    handle(press({ key: 'g', ctrlKey: true }))
    handle(press({ key: 'c' }))
    handle(press({ key: 'g', metaKey: true }))
    handle(press({ key: 'c' }))
    handle(press({ key: 'g', altKey: true }))
    handle(press({ key: 'c' }))

    expect(navigate).not.toHaveBeenCalled()
  })
})

describe('the shortcut list', () => {
  it('opens the help on ? wherever the focus is outside a text field', () => {
    const { openHelp, handle } = setup()

    handle(press({ key: '?', shiftKey: true }))

    expect(openHelp).toHaveBeenCalledOnce()
  })

  it('does not treat ? as a chord partner of g', () => {
    const { openHelp, navigate, handle } = setup()

    handle(press({ key: 'g' }))
    handle(press({ key: '?', shiftKey: true }))
    handle(press({ key: 'c' }))

    expect(openHelp).toHaveBeenCalledOnce()
    expect(navigate, 'g was cancelled by ?').not.toHaveBeenCalled()
  })
})

describe('when the keys belong to someone else', () => {
  it.each([
    ['an input', () => typeInto(document.createElement('input'))],
    ['a textarea', () => typeInto(document.createElement('textarea'))],
    ['a select', () => typeInto(document.createElement('select'))],
    ['a contenteditable element', () => typeInto(editable())],
  ])('does not navigate or open the help while typing in %s', (_name, make) => {
    const { navigate, openHelp, handle } = setup()
    const field = make()

    handle(press({ key: 'g', target: field }))
    handle(press({ key: 'c', target: field }))
    handle(press({ key: '?', target: field, shiftKey: true }))

    expect(navigate).not.toHaveBeenCalled()
    expect(openHelp).not.toHaveBeenCalled()
  })

  it('does not complete a chord that was started outside a field when the next key is typed in one', () => {
    const { navigate, handle } = setup()
    const field = typeInto(document.createElement('input'))

    handle(press({ key: 'g' }))
    handle(press({ key: 'c', target: field }))

    expect(navigate, 'the typed c').not.toHaveBeenCalled()
  })

  it('does nothing while an open dialog or modal navigation is on the page', () => {
    const { navigate, openHelp, handle } = setup()
    const dialog = typeInto(document.createElement('div'))
    dialog.setAttribute('role', 'dialog')

    handle(press({ key: 'g' }))
    handle(press({ key: 'c' }))
    handle(press({ key: '?', shiftKey: true }))

    expect(navigate).not.toHaveBeenCalled()
    expect(openHelp).not.toHaveBeenCalled()
  })

  it('does nothing when the caller says the shortcuts are off, for example without a role', () => {
    const { navigate, openHelp, handle } = setup({ enabled: false })

    handle(press({ key: 'g' }))
    handle(press({ key: 'c' }))
    handle(press({ key: '?', shiftKey: true }))

    expect(navigate).not.toHaveBeenCalled()
    expect(openHelp).not.toHaveBeenCalled()
  })
})

describe('isTextEntry', () => {
  it('is false for the page itself and for buttons and links', () => {
    expect(isTextEntry(document.body)).toBe(false)
    expect(isTextEntry(document.createElement('button'))).toBe(false)
    expect(isTextEntry(null)).toBe(false)
  })

  it('is true for a text input, a text area, a select and editable content', () => {
    expect(isTextEntry(document.createElement('input'))).toBe(true)
    expect(isTextEntry(document.createElement('textarea'))).toBe(true)
    expect(isTextEntry(document.createElement('select'))).toBe(true)
    expect(isTextEntry(editable())).toBe(true)
  })
})
