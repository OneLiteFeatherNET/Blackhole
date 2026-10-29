import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { AllowListEntry } from '../../api/types'
import { chooseOption, listOptions } from '../../test/select'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import AllowListForm from './AllowListForm.vue'

const OWNER = 'ab'.repeat(64)
const NOW = Date.UTC(2030, 5, 1)
const DAY = 24 * 60 * 60 * 1000

function entry(overrides: Partial<AllowListEntry> = {}): AllowListEntry {
  return { owner: OWNER, reason: 'ticket 12', actorOid: 'staff-1', createdAt: NOW - DAY, expiresAt: NOW + DAY, ...overrides }
}

function mountForm(props: { administrator: boolean; entries?: AllowListEntry[] }, api: Record<string, unknown> = {}) {
  const resolvePlayer = vi.fn(async () => ok({ owner: OWNER } as never))
  const addIntelAllowListEntry = vi.fn(async () => ok(entry()))
  return {
    resolvePlayer,
    addIntelAllowListEntry,
    mounted: mountWithApp(AllowListForm, {
      api: mockApi({ resolvePlayer, addIntelAllowListEntry, ...api }),
      props: { entries: [], now: NOW, ...props },
    }),
  }
}

describe('AllowListForm', () => {
  it('keeps submit disabled and explains the reason is missing', async () => {
    const { mounted } = mountForm({ administrator: false })
    const { wrapper } = await mounted

    await wrapper.findAll('input')[0]!.setValue('Steve')
    expect(wrapper.get('[data-testid="allow-list-submit"]').attributes('disabled'), 'no reason yet').toBeDefined()

    await wrapper.findAll('input')[1]!.setValue('   ')
    expect(wrapper.text(), 'the field says what is missing').toContain('Give a reason')
    expect(wrapper.get('[data-testid="allow-list-submit"]').attributes('disabled')).toBeDefined()

    wrapper.unmount()
  })

  it('offers no permanent duration to a moderator', async () => {
    const { mounted } = mountForm({ administrator: false })
    const { wrapper } = await mounted

    const values = (await listOptions(wrapper.get('[role="combobox"]'))).map((option) => option.value)

    expect(values).toEqual(['1', '7', '30', '90'])

    wrapper.unmount()
  })

  it('offers a permanent duration to an administrator', async () => {
    const { mounted } = mountForm({ administrator: true })
    const { wrapper } = await mounted

    const values = (await listOptions(wrapper.get('[role="combobox"]'))).map((option) => option.value)

    expect(values).toContain('permanent')

    wrapper.unmount()
  })

  it('exempts the player for the chosen days and reports the stored entry', async () => {
    const { mounted, resolvePlayer, addIntelAllowListEntry } = mountForm({ administrator: false })
    const { wrapper } = await mounted

    await wrapper.findAll('input')[0]!.setValue('Steve')
    await wrapper.findAll('input')[1]!.setValue('ticket 12')
    await chooseOption(wrapper.get('[role="combobox"]'), '7')
    await wrapper.get('[data-testid="allow-list-submit"]').trigger('click')
    await flushPromises()

    expect(resolvePlayer).toHaveBeenCalledWith('Steve')
    expect(addIntelAllowListEntry).toHaveBeenCalledWith({ owner: OWNER, reason: 'ticket 12', durationDays: 7 })
    expect(wrapper.emitted('added')?.[0]).toEqual([entry()])

    wrapper.unmount()
  })

  it('sends no duration for a permanent exemption', async () => {
    const { mounted, addIntelAllowListEntry } = mountForm({ administrator: true })
    const { wrapper } = await mounted

    await wrapper.findAll('input')[0]!.setValue('Steve')
    await wrapper.findAll('input')[1]!.setValue('appeal granted')
    await chooseOption(wrapper.get('[role="combobox"]'), 'permanent')
    await wrapper.get('[data-testid="allow-list-submit"]').trigger('click')
    await flushPromises()

    expect(addIntelAllowListEntry).toHaveBeenCalledWith({ owner: OWNER, reason: 'appeal granted', durationDays: null })

    wrapper.unmount()
  })

  it('shows an entry whose expiry has passed as expired and a running one as running', async () => {
    const expired = entry({ expiresAt: NOW - 1 })
    const running = entry({ owner: 'cd'.repeat(64), expiresAt: NOW + DAY })
    const { mounted } = mountForm({ administrator: false, entries: [expired, running] })
    const { wrapper } = await mounted

    const items = wrapper.findAll('[data-testid="allow-list-entry"]')
    expect(items[0]!.text()).toContain('Expired')
    expect(items[1]!.text()).not.toContain('Expired')

    wrapper.unmount()
  })

  it('shows a refusal of the backend and keeps the typed reason', async () => {
    const { mounted } = mountForm({ administrator: false }, { addIntelAllowListEntry: vi.fn(async () => fail('the reason is too long', 400)) })
    const { wrapper } = await mounted

    await wrapper.findAll('input')[0]!.setValue('Steve')
    await wrapper.findAll('input')[1]!.setValue('mine')
    await wrapper.get('[data-testid="allow-list-submit"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="allow-list-error"]').text()).toContain('the reason is too long')
    expect((wrapper.findAll('input')[1]!.element as HTMLInputElement).value).toBe('mine')

    wrapper.unmount()
  })
})
