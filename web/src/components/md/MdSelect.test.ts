import { mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import MdSelect, { type SelectOption } from './MdSelect.vue'

const options: SelectOption[] = [
  { value: 'system', label: 'System' },
  { value: 'light', label: 'Hell' },
  { value: 'dark', label: 'Dunkel' },
  { value: 'old', label: 'Alt', disabled: true, description: 'Deaktiviert' },
  { value: 'hc', label: 'Hoher Kontrast' },
]

const mounted: VueWrapper[] = []

beforeEach(() => vi.useFakeTimers())

function setup(modelValue = 'system', extra: Record<string, unknown> = {}) {
  const onUpdate = vi.fn()
  const wrapper = mount(MdSelect, {
    props: { label: 'Design', modelValue, options, 'onUpdate:modelValue': onUpdate, ...extra },
    attachTo: document.body,
  })
  mounted.push(wrapper)
  const combobox = wrapper.get('[role="combobox"]')
  ;(combobox.element as HTMLElement).focus()
  const list = () => document.getElementById(combobox.attributes('aria-controls')!)
  const items = () => Array.from(list()?.querySelectorAll('[role="option"]') ?? [])
  const press = async (key: string) => {
    await combobox.trigger('keydown', { key })
    await nextTick()
  }
  return { wrapper, combobox, list, items, press, onUpdate }
}

afterEach(() => {
  vi.useRealTimers()
  mounted.splice(0).forEach((wrapper) => wrapper.unmount())
})

describe('MdSelect', () => {
  it('shows the selected label in a combobox named by its label and value', () => {
    const { combobox } = setup('light')

    expect(combobox.text()).toBe('Hell')
    expect(combobox.attributes('aria-haspopup')).toBe('listbox')
    expect(combobox.attributes('aria-expanded')).toBe('false')
    const names = combobox.attributes('aria-labelledby')!.split(' ').map((id) => document.getElementById(id)?.textContent?.trim())
    expect(names).toEqual(['Design', 'Hell'])
  })

  it('renders no menu while closed and a listbox of options in the body once opened', async () => {
    const { combobox, list, items } = setup('light')
    expect(list()).toBeNull()

    await combobox.trigger('click')

    expect(combobox.attributes('aria-expanded')).toBe('true')
    expect(list()?.getAttribute('role')).toBe('listbox')
    expect(list()?.parentElement).toBe(document.body)
    expect(items().map((item) => item.textContent?.trim())).toEqual(['System', 'Hell', 'Dunkel', 'AltDeaktiviert', 'Hoher Kontrast'])
  })

  it('marks the selected option and points the combobox at the highlighted one', async () => {
    const { combobox, items } = setup('light')

    await combobox.trigger('click')

    expect(items().map((item) => item.getAttribute('aria-selected'))).toEqual(['false', 'true', 'false', 'false', 'false'])
    expect(combobox.attributes('aria-activedescendant')).toBe(items()[1]!.id)
  })

  it('marks a disabled option aria-disabled and does not choose it on click', async () => {
    const { combobox, items, onUpdate } = setup()
    await combobox.trigger('click')

    expect(items()[3]!.getAttribute('aria-disabled')).toBe('true')
    ;(items()[3] as HTMLElement).click()
    await nextTick()

    expect(onUpdate).not.toHaveBeenCalled()
    expect(combobox.attributes('aria-expanded')).toBe('true')
  })

  it('emits the clicked option, closes the menu and returns focus to the field', async () => {
    const { combobox, items, list, onUpdate } = setup()
    await combobox.trigger('click')
    ;(items()[2] as HTMLElement).click()
    await nextTick()

    expect(onUpdate).toHaveBeenLastCalledWith('dark')
    expect(list()).toBeNull()
    expect(document.activeElement).toBe(combobox.element)
  })

  it('rotates the arrow while open', async () => {
    const { wrapper, combobox } = setup()
    expect(wrapper.get('[data-testid="arrow"]').classes()).not.toContain('rotate-180')

    await combobox.trigger('click')

    expect(wrapper.get('[data-testid="arrow"]').classes()).toContain('rotate-180')
  })

  describe('keyboard', () => {
    it.each(['ArrowDown', 'ArrowUp', 'Enter', ' '])('opens the menu on %j', async (key) => {
      const { combobox, press } = setup()

      await press(key)

      expect(combobox.attributes('aria-expanded')).toBe('true')
    })

    it('moves the highlight with the arrow keys and skips disabled options', async () => {
      const { combobox, items, press } = setup('dark')
      await press('ArrowDown')
      expect(combobox.attributes('aria-activedescendant')).toBe(items()[2]!.id)

      await press('ArrowDown')
      expect(combobox.attributes('aria-activedescendant'), 'skips "Alt"').toBe(items()[4]!.id)

      await press('ArrowDown')
      expect(combobox.attributes('aria-activedescendant'), 'stops at the end').toBe(items()[4]!.id)

      await press('ArrowUp')
      expect(combobox.attributes('aria-activedescendant')).toBe(items()[2]!.id)
    })

    it('jumps to the first and last enabled option with Home and End', async () => {
      const { combobox, items, press } = setup('light')
      await press('ArrowDown')

      await press('End')
      expect(combobox.attributes('aria-activedescendant')).toBe(items()[4]!.id)

      await press('Home')
      expect(combobox.attributes('aria-activedescendant')).toBe(items()[0]!.id)
    })

    it.each(['Enter', ' '])('chooses the highlighted option with %j', async (key) => {
      const { press, onUpdate, list } = setup('system')
      await press('ArrowDown')
      await press('ArrowDown')

      await press(key)

      expect(onUpdate).toHaveBeenLastCalledWith('light')
      expect(list()).toBeNull()
    })

    it('closes with Escape without choosing and keeps the Escape from reaching a dialog', async () => {
      const { wrapper, combobox, press, onUpdate, list } = setup()
      const outer = vi.fn()
      wrapper.element.parentElement!.addEventListener('keydown', outer)
      await press('ArrowDown')
      await press('ArrowDown')

      await press('Escape')

      expect(list()).toBeNull()
      expect(onUpdate).not.toHaveBeenCalled()
      expect(outer.mock.calls.filter(([event]) => (event as KeyboardEvent).key === 'Escape')).toHaveLength(0)
      expect(document.activeElement).toBe(combobox.element)
    })

    it('lets Escape through while the menu is closed so a dialog can close', async () => {
      const { wrapper, press } = setup()
      const outer = vi.fn()
      wrapper.element.parentElement!.addEventListener('keydown', outer)

      await press('Escape')

      expect(outer).toHaveBeenCalledOnce()
    })

    it('jumps to an option by typing its first letters', async () => {
      const { combobox, items, press } = setup('system')
      await press('ArrowDown')

      await press('d')
      expect(combobox.attributes('aria-activedescendant')).toBe(items()[2]!.id)

      vi.advanceTimersByTime(1000)
      await press('h')
      await press('o')
      expect(combobox.attributes('aria-activedescendant'), '"ho" finds Hoher Kontrast').toBe(items()[4]!.id)
    })

    it('does not land on a disabled option when typing', async () => {
      const { combobox, items, press } = setup('system')
      await press('ArrowDown')

      await press('a')

      expect(combobox.attributes('aria-activedescendant'), 'no match, highlight stays').toBe(items()[0]!.id)
    })

    it('opens the menu and highlights the match when typing on the closed field', async () => {
      const { combobox, items, press } = setup('system')

      await press('d')

      expect(combobox.attributes('aria-expanded')).toBe('true')
      expect(combobox.attributes('aria-activedescendant')).toBe(items()[2]!.id)
    })

    it('closes on Tab and takes the highlighted option', async () => {
      const { press, list, onUpdate } = setup('system')
      await press('ArrowDown')
      await press('ArrowDown')

      await press('Tab')

      expect(list()).toBeNull()
      expect(onUpdate).toHaveBeenLastCalledWith('light')
    })
  })

  it('closes when the pointer goes down outside and stays open for a press on the field', async () => {
    const { combobox, list } = setup()
    await combobox.trigger('click')

    document.body.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    await nextTick()

    expect(list()).toBeNull()
  })

  it('does not open when disabled', async () => {
    const { combobox, list } = setup('system', { disabled: true })

    await combobox.trigger('click')

    expect(list()).toBeNull()
    expect(combobox.attributes('disabled')).toBeDefined()
  })

  it('shows supporting text and links an error to the field', () => {
    const { wrapper, combobox } = setup('system', { supportingText: 'Hilfe', error: 'Pflichtfeld' })

    expect(wrapper.text()).toContain('Pflichtfeld')
    expect(wrapper.text()).not.toContain('Hilfe')
    expect(combobox.attributes('aria-invalid')).toBe('true')
    expect(document.getElementById(combobox.attributes('aria-describedby')!)?.textContent).toContain('Pflichtfeld')
  })

  describe('placeholder', () => {
    const empty = { placeholder: 'Wählen', required: true }

    it('is the empty-state text of the field, not an option', async () => {
      const { combobox, items } = setup('', empty)

      expect(combobox.text()).toBe('Wählen')
      expect(combobox.get('[data-testid="placeholder"]').classes()).toContain('text-on-surface-variant')

      await combobox.trigger('click')

      expect(items().map((item) => item.textContent?.trim())).not.toContain('Wählen')
      expect(items()).toHaveLength(options.length)
    })

    it('checks no option and highlights none until the user navigates', async () => {
      const { combobox, items, press } = setup('', empty)

      await combobox.trigger('click')

      expect(items().every((item) => item.getAttribute('aria-selected') === 'false')).toBe(true)
      expect(combobox.attributes('aria-activedescendant')).toBeUndefined()

      await press('ArrowDown')

      expect(combobox.attributes('aria-activedescendant')).toBe(items()[0]!.id)
    })

    it('does not choose anything when Enter is pressed before navigating', async () => {
      const { combobox, press, onUpdate } = setup('', empty)
      await combobox.trigger('click')

      await press('Enter')

      expect(onUpdate).not.toHaveBeenCalled()
      expect(combobox.attributes('aria-expanded')).toBe('false')
    })

    it('names the combobox by label and placeholder and flags it required', () => {
      const { combobox } = setup('', empty)

      const names = combobox.attributes('aria-labelledby')!.split(' ').map((id) => document.getElementById(id)?.textContent?.trim())
      expect(names).toEqual(['Design', 'Wählen'])
      expect(combobox.attributes('aria-required')).toBe('true')
    })

    it('opens on the first option with ArrowDown and the last with ArrowUp when nothing is selected', async () => {
      const down = setup('', empty)
      await down.press('ArrowDown')
      expect(down.combobox.attributes('aria-activedescendant')).toBe(down.items()[0]!.id)
      down.wrapper.unmount()

      const up = setup('', empty)
      await up.press('ArrowUp')
      expect(up.combobox.attributes('aria-activedescendant')).toBe(up.items()[4]!.id)
    })
  })

  it('bounds the open menu and positions it at the field width', async () => {
    const { combobox, list } = setup('system')

    await combobox.trigger('click')

    expect(list()!.style.maxHeight).toMatch(/^\d+(\.\d+)?px$/)
    expect(Number.parseFloat(list()!.style.maxHeight)).toBeLessThanOrEqual(5.5 * 48 + 16)
  })
})
