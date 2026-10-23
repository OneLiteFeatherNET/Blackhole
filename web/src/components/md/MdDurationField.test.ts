import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '../../i18n'
import MdDurationField from './MdDurationField.vue'

beforeEach(() => {
  i18n.global.locale.value = 'de'
})

function setup(modelValue = '', extra: Record<string, unknown> = {}) {
  const onUpdate = vi.fn()
  const wrapper = mount(MdDurationField, {
    props: { label: 'Dauer', modelValue, 'onUpdate:modelValue': onUpdate, ...extra },
    global: { plugins: [i18n] },
  })
  const input = wrapper.get('input')
  return { wrapper, input, onUpdate }
}

const message = (wrapper: ReturnType<typeof setup>['wrapper']) =>
  wrapper.get(`[id="${wrapper.get('input').attributes('aria-describedby')}"]`).text()

describe('MdDurationField', () => {
  it('shows a stored ISO value in the short form', () => {
    expect(setup('P21D').input.element.value).toBe('3w')
    expect(setup('PT90M').input.element.value).toBe('1h 30m')
  })

  it('emits the ISO-8601 form of what was typed', async () => {
    const { input, onUpdate } = setup()

    await input.setValue('1w 2d')

    expect(onUpdate).toHaveBeenLastCalledWith('P9D')
  })

  it('accepts raw ISO-8601 from power users', async () => {
    const { input, onUpdate } = setup()

    await input.setValue('PT12H')

    expect(onUpdate).toHaveBeenLastCalledWith('PT12H')
  })

  it('previews the value in words while typing', async () => {
    const { wrapper, input } = setup()

    await input.setValue('3w')

    expect(message(wrapper)).toBe(`= ${new Intl.NumberFormat('de', { style: 'unit', unit: 'week', unitDisplay: 'long' }).format(3)}`)
  })

  it('shows the hint while empty', () => {
    const { wrapper } = setup('', { supportingText: 'Optional' })

    expect(message(wrapper)).toBe('Optional')
  })

  it('shows no error while typing but explains the problem after leaving the field', async () => {
    const { wrapper, input } = setup()

    await input.setValue('3')
    expect(input.attributes('aria-invalid')).toBeUndefined()

    await input.trigger('focusout')

    expect(input.attributes('aria-invalid')).toBe('true')
    expect(message(wrapper)).toContain('Einheit')
  })

  it('explains why months are not accepted', async () => {
    const { wrapper, input } = setup()

    await input.setValue('2mo')
    await input.trigger('focusout')

    expect(message(wrapper)).toContain('Monate')
  })

  it('emits the raw text while it is not a duration so the form can refuse it', async () => {
    const { input, onUpdate } = setup()

    await input.setValue('soon')

    expect(onUpdate).toHaveBeenLastCalledWith('soon')
  })

  it('shows an error from outside', () => {
    const { wrapper, input } = setup('P1D', { error: 'Server says no' })

    expect(input.attributes('aria-invalid')).toBe('true')
    expect(message(wrapper)).toBe('Server says no')
  })

  it('tidies the typed text into the short form when the field is left', async () => {
    const { input } = setup()

    await input.setValue('90m')
    await input.trigger('focusout')

    expect(input.element.value).toBe('1h 30m')
  })

  it('follows a value replaced from outside', async () => {
    const { wrapper, input } = setup('P1D')

    await wrapper.setProps({ modelValue: 'P14D' })

    expect(input.element.value).toBe('2w')
  })

  describe('quick picks', () => {
    it('offers the chips in a labelled group and marks the one that matches', async () => {
      const { wrapper } = setup('P7D', { chips: ['5m', '1h', '7d'] })

      expect(wrapper.get('[role="group"]').attributes('aria-label')).toBeTruthy()
      expect(wrapper.findAll('button').map((b) => [b.text(), b.attributes('aria-pressed')])).toEqual([
        ['5m', 'false'],
        ['1h', 'false'],
        ['7d', 'true'],
      ])
    })

    it('fills the field and emits the ISO value when a chip is clicked', async () => {
      const { wrapper, input, onUpdate } = setup('', { chips: ['5m', '1h'] })

      await wrapper.get('[data-testid="chip-1h"]').trigger('click')

      expect(input.element.value).toBe('1h')
      expect(onUpdate).toHaveBeenLastCalledWith('PT1H')
    })
  })
})
