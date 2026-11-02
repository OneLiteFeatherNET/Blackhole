import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import StatusPill from './StatusPill.vue'

describe('StatusPill', () => {
  it.each([
    ['neutral', 'bg-surface-container-highest'],
    ['outline', 'border-outline'],
    ['error', 'bg-error-container'],
    ['primary', 'bg-primary-container'],
    ['secondary', 'bg-secondary-container'],
    ['tertiary', 'bg-tertiary-container'],
    ['success', 'bg-success-container'],
    ['warning', 'bg-warning-container'],
  ] as const)('gives the %s tone its own container colour', (tone, expectedClass) => {
    const wrapper = mount(StatusPill, { props: { label: 'Open', tone } })

    expect(wrapper.classes()).toContain(expectedClass)
    expect(wrapper.text()).toBe('Open')
  })

  it('is neutral when no tone is given', () => {
    const wrapper = mount(StatusPill, { props: { label: 'Open' } })

    expect(wrapper.classes()).toContain('bg-surface-container-highest')
  })

  it('shows its label whatever the tone, so the colour is never the only signal', () => {
    const wrapper = mount(StatusPill, { props: { label: 'Urgent', tone: 'error' } })

    expect(wrapper.text()).toBe('Urgent')
  })
})
