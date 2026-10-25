import { flushPromises } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { mountWithApp } from '../test/mount'
import FilterToolbar, { type FilterDef } from './FilterToolbar.vue'

const STATUS: FilterDef = {
  kind: 'toggle-group',
  id: 'status',
  label: 'Status',
  options: [
    { value: '', label: 'All statuses' },
    { value: 'OPEN', label: 'Open' },
    { value: 'CLOSED', label: 'Closed' },
  ],
}
const SECTION: FilterDef = {
  kind: 'select',
  id: 'section',
  label: 'Section',
  options: [
    { value: 'network', label: 'Network' },
    { value: 'chat', label: 'Chat' },
  ],
}
const SEARCH: FilterDef = { kind: 'search', id: 'q', label: 'Search' }
const TAG: FilterDef = { kind: 'tag', id: 'tag', label: 'Tag', pattern: /^[a-z0-9-]{1,32}$/, invalid: 'Tags are short.' }

function setup(filters: FilterDef[], modelValue: Record<string, string> = {}) {
  return mountWithApp(FilterToolbar, { props: { filters, modelValue } })
}

/** The last value the toolbar asked for; each test mounts its own toolbar, so nothing is shared. */
function lastEmitted(wrapper: Awaited<ReturnType<typeof setup>>['wrapper']): Record<string, string> | undefined {
  return wrapper.emitted<Record<string, string>>('update:modelValue')?.at(-1)?.[0]
}

describe('FilterToolbar: the four kinds', () => {
  it('renders a toggle group whose selected option is pressed', async () => {
    const { wrapper } = await setup([STATUS], { status: 'OPEN' })

    expect(wrapper.get('[data-testid="status-OPEN"]').attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('[data-testid="status-all"]').attributes('aria-pressed')).toBe('false')

    wrapper.unmount()
  })

  it('emits the new value when a toggle is pressed and clears it when the selected toggle is pressed again', async () => {
    const { wrapper } = await setup([STATUS], { status: 'OPEN' })

    await wrapper.get('[data-testid="status-CLOSED"]').trigger('click')
    expect(lastEmitted(wrapper)).toEqual({ status: 'CLOSED' })

    await wrapper.get('[data-testid="status-OPEN"]').trigger('click')
    expect(lastEmitted(wrapper)).toEqual({ status: '' })

    wrapper.unmount()
  })

  it('renders a select with its options and emits the chosen value', async () => {
    const { wrapper } = await setup([SECTION])

    expect(wrapper.get('[data-testid="section"]').find('[role="combobox"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('renders a search field that shows its value', async () => {
    const { wrapper } = await setup([SEARCH], { q: 'spam' })

    expect((wrapper.get('[data-testid="q"] input').element as HTMLInputElement).value).toBe('spam')

    wrapper.unmount()
  })

  it('turns a valid tag into a value on submit without a submit button', async () => {
    const { wrapper } = await setup([TAG])

    expect(wrapper.find('[data-testid="tag-filter"] button[type="submit"]').exists()).toBe(false)
    await wrapper.get('[data-testid="tag-filter"] input').setValue('Spam')
    await wrapper.get('[data-testid="tag-filter"]').trigger('submit')
    await flushPromises()

    expect(lastEmitted(wrapper)).toEqual({ tag: 'spam' })

    wrapper.unmount()
  })

  it('refuses an invalid tag and emits nothing', async () => {
    const { wrapper } = await setup([TAG])

    await wrapper.get('[data-testid="tag-filter"] input').setValue('Not A Tag')
    await wrapper.get('[data-testid="tag-filter"]').trigger('submit')
    await flushPromises()

    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(wrapper.text()).toContain('Tags are short.')

    wrapper.unmount()
  })
})

describe('FilterToolbar: one row, one order', () => {
  it('keeps every control in the single toolbar row, in the order search, select, toggle, tag', async () => {
    const { wrapper } = await setup([TAG, STATUS, SECTION, SEARCH])

    const row = wrapper.get('[data-testid="filter-toolbar"]')
    const order = row.findAll('[data-filter-kind]').map((control) => control.attributes('data-filter-kind'))
    expect(order).toEqual(['search', 'select', 'toggle-group', 'tag'])

    wrapper.unmount()
  })
})

describe('FilterToolbar: active filters as input chips', () => {
  it('shows an input chip for every applied filter, with its value', async () => {
    const { wrapper } = await setup([STATUS, TAG], { status: 'CLOSED', tag: 'spam' })

    const chips = wrapper.get('[data-testid="applied-filters"]').text()
    expect(chips).toContain('Closed')
    expect(chips).toContain('Tag: spam')

    wrapper.unmount()
  })

  it('shows no chip row when nothing is applied', async () => {
    const { wrapper } = await setup([STATUS, TAG])

    expect(wrapper.find('[data-testid="applied-filters"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('removes one filter from its chip', async () => {
    const { wrapper } = await setup([TAG], { tag: 'spam' })

    await wrapper.get('[aria-label="Remove filter Tag: spam"]').trigger('click')

    expect(lastEmitted(wrapper)).toEqual({ tag: '' })

    wrapper.unmount()
  })

  it('offers "Clear all" only when more than one filter is applied', async () => {
    const one = await setup([STATUS, TAG], { status: 'CLOSED' })
    expect(one.wrapper.find('[data-testid="clear-all"]').exists()).toBe(false)
    one.wrapper.unmount()

    const two = await setup([STATUS, TAG], { status: 'CLOSED', tag: 'spam' })
    await two.wrapper.get('[data-testid="clear-all"]').trigger('click')

    expect(lastEmitted(two.wrapper)).toEqual({ status: '', tag: '' })
    two.wrapper.unmount()
  })
})
