import { flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { describe, expect, it } from 'vitest'
import { mountWithApp } from '../test/mount'
import { choiceOf, useQueryChoice } from './queryChoice'

const TABS = ['vanilla', 'punishments'] as const

describe('choiceOf', () => {
  it('returns the value of an allowed query key', () => {
    expect(choiceOf({ tab: 'punishments' }, 'tab', TABS, 'vanilla')).toBe('punishments')
  })

  it('returns the fallback for a missing key', () => {
    expect(choiceOf({}, 'tab', TABS, 'vanilla')).toBe('vanilla')
  })

  it('returns the fallback for a value that is not allowed', () => {
    expect(choiceOf({ tab: 'unknown' }, 'tab', TABS, 'vanilla')).toBe('vanilla')
  })

  it('returns the fallback for a repeated key, which is not a single choice', () => {
    expect(choiceOf({ tab: ['punishments', 'vanilla'] }, 'tab', TABS, 'vanilla')).toBe('vanilla')
  })
})

describe('useQueryChoice', () => {
  const Probe = defineComponent({
    setup() {
      const tab = useQueryChoice('tab', TABS, 'vanilla')
      return () => h('p', { 'data-testid': 'tab' }, tab.value)
    },
  })

  it('replaces an unknown value in the address by removing it, and keeps the other query keys', async () => {
    const { wrapper, router } = await mountWithApp(Probe, { path: '/admin/imports?tab=unknown&group=x', roles: ['ADMIN'] })
    await flushPromises()

    expect(wrapper.get('[data-testid="tab"]').text()).toBe('vanilla')
    expect(router.currentRoute.value.query).toEqual({ group: 'x' })

    wrapper.unmount()
  })

  it('leaves a known value in the address', async () => {
    const { wrapper, router } = await mountWithApp(Probe, { path: '/admin/imports?tab=punishments', roles: ['ADMIN'] })
    await flushPromises()

    expect(wrapper.get('[data-testid="tab"]').text()).toBe('punishments')
    expect(router.currentRoute.value.query).toEqual({ tab: 'punishments' })

    wrapper.unmount()
  })
})
