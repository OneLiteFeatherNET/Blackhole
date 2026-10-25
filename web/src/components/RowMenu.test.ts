import { describe, expect, it } from 'vitest'
import { mountWithApp } from '../test/mount'
import RowMenu, { type RowMenuItem } from './RowMenu.vue'

const ITEMS: RowMenuItem[] = [
  { key: 'edit', label: 'Edit' },
  { key: 'duplicate', label: 'Duplicate' },
  { key: 'delete', label: 'Delete', destructive: true },
]

function setup(items: RowMenuItem[] = ITEMS) {
  return mountWithApp(RowMenu, { props: { label: '#AB12CD', items } })
}

async function open(wrapper: Awaited<ReturnType<typeof setup>>['wrapper']) {
  await wrapper.get('button').trigger('click')
}

describe('RowMenu: the trigger', () => {
  it('names the trigger for its row and says that it opens a menu', async () => {
    const { wrapper } = await setup()

    const trigger = wrapper.get('button')
    expect(trigger.attributes('aria-label')).toBe('More actions for #AB12CD')
    expect(trigger.attributes('aria-haspopup')).toBe('menu')
    expect(trigger.attributes('aria-expanded')).toBe('false')

    wrapper.unmount()
  })

  it('opens on click, lists its items as menu items and focuses the first one', async () => {
    const { wrapper } = await setup()

    await open(wrapper)

    expect(wrapper.get('button').attributes('aria-expanded')).toBe('true')
    expect(wrapper.findAll('[role="menuitem"]').map((item) => item.text())).toEqual(['Edit', 'Duplicate', 'Delete'])
    expect(document.activeElement).toBe(wrapper.findAll('[role="menuitem"]')[0]!.element)

    wrapper.unmount()
  })
})

describe('RowMenu: keyboard', () => {
  it('moves the focus down and wraps from the last item to the first', async () => {
    const { wrapper } = await setup()
    await open(wrapper)
    const menu = wrapper.get('[role="menu"]')

    await menu.trigger('keydown', { key: 'ArrowDown' })
    expect(document.activeElement).toBe(wrapper.findAll('[role="menuitem"]')[1]!.element)

    await menu.trigger('keydown', { key: 'ArrowDown' })
    await menu.trigger('keydown', { key: 'ArrowDown' })
    expect(document.activeElement, 'wraps to the first item').toBe(wrapper.findAll('[role="menuitem"]')[0]!.element)

    wrapper.unmount()
  })

  it('moves the focus up and wraps from the first item to the last', async () => {
    const { wrapper } = await setup()
    await open(wrapper)

    await wrapper.get('[role="menu"]').trigger('keydown', { key: 'ArrowUp' })

    expect(document.activeElement, 'wraps to the last item').toBe(wrapper.findAll('[role="menuitem"]')[2]!.element)

    wrapper.unmount()
  })

  it('jumps to the last and the first item with End and Home', async () => {
    const { wrapper } = await setup()
    await open(wrapper)
    const menu = wrapper.get('[role="menu"]')

    await menu.trigger('keydown', { key: 'End' })
    expect(document.activeElement).toBe(wrapper.findAll('[role="menuitem"]')[2]!.element)

    await menu.trigger('keydown', { key: 'Home' })
    expect(document.activeElement).toBe(wrapper.findAll('[role="menuitem"]')[0]!.element)

    wrapper.unmount()
  })

  it('closes on Escape and returns the focus to the trigger', async () => {
    const { wrapper } = await setup()
    await open(wrapper)

    await wrapper.get('[role="menu"]').trigger('keydown', { key: 'Escape' })

    expect(wrapper.find('[role="menu"]').exists()).toBe(false)
    expect(document.activeElement).toBe(wrapper.get('button').element)

    wrapper.unmount()
  })
})

describe('RowMenu: choosing an item', () => {
  it('emits the key of the chosen item and closes the menu', async () => {
    const { wrapper } = await setup()
    await open(wrapper)

    await wrapper.findAll('[role="menuitem"]')[1]!.trigger('click')

    expect(wrapper.emitted('select')).toEqual([['duplicate']])
    expect(wrapper.find('[role="menu"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows a destructive item in the error colour', async () => {
    const { wrapper } = await setup()
    await open(wrapper)

    expect(wrapper.findAll('[role="menuitem"]')[2]!.classes()).toContain('text-error')
    expect(wrapper.findAll('[role="menuitem"]')[0]!.classes()).not.toContain('text-error')

    wrapper.unmount()
  })
})
