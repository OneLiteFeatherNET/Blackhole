import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { AuditEntry } from '../../api/types'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import { chooseOption } from '../../test/select'
import AuditLogPanel from './AuditLogPanel.vue'

const ADMIN_OID = '00000000-0000-4000-8000-0000000000a1'
const MODERATOR_OID = '00000000-0000-4000-8000-0000000000b2'

function entry(overrides: Partial<AuditEntry> = {}): AuditEntry {
  return {
    auditId: '11111111-1111-4111-8111-111111111111',
    occurredAt: Date.UTC(2026, 9, 28, 12, 0),
    actorOid: MODERATOR_OID,
    actorName: 'Mod Ann',
    surface: 'PANEL',
    action: 'punishment.revoke',
    targetKind: 'CASE',
    targetRef: 'ABC123',
    panelPath: '/cases/ABC123',
    recordedByOid: MODERATOR_OID,
    ...overrides,
  }
}

const REVOKE = entry()
const PLAYER_KICK = entry({
  auditId: '22222222-2222-4222-8222-222222222222',
  actorOid: ADMIN_OID,
  actorName: null,
  surface: 'IN_GAME',
  action: 'kick.create',
  targetKind: 'PLAYER',
  targetRef: 'cd'.repeat(32),
  panelPath: `/players/${'cd'.repeat(32)}`,
})
const SECTION_UNLINK = entry({
  auditId: '33333333-3333-4333-8333-333333333333',
  actorOid: ADMIN_OID,
  actorName: 'Admin Bo',
  surface: 'DISCORD',
  action: 'staff-link.unlink',
  targetKind: 'SECTION',
  targetRef: 'network',
  panelPath: null,
})

function setup(overrides: Record<string, unknown> = {}) {
  const api = {
    auditLog: vi.fn(async () => ok({ items: [REVOKE, PLAYER_KICK], nextCursor: null })),
    ...overrides,
  }
  return { api, mount: () => mountWithApp(AuditLogPanel, { api: mockApi(api), roles: ['ADMIN'], path: '/admin/audit' }) }
}

type Page = Awaited<ReturnType<ReturnType<typeof setup>['mount']>>['wrapper']

function rowsOf(wrapper: Page) {
  return wrapper.findAll('tbody tr')
}

describe('AuditLogPanel: list', () => {
  it('asks for the first page of 50 entries with no filter and no cursor', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    expect(api.auditLog).toHaveBeenCalledWith({ limit: 50 })

    wrapper.unmount()
  })

  it('shows who, surface, action, target and time of each entry in the order received', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    const rows = rowsOf(wrapper)
    expect(rows).toHaveLength(2)
    expect(rows[0]!.text()).toContain('Mod Ann')
    expect(rows[0]!.text()).toContain('Panel')
    expect(rows[0]!.text()).toContain('Revoke punishment')
    expect(rows[0]!.text()).toContain('Case ABC123')
    expect(rows[0]!.text()).toContain('2026')
    expect(rows[1]!.text()).toContain('In game')
    expect(rows[1]!.text()).toContain('Kick player')

    wrapper.unmount()
  })

  it('names an entry without a display name by the start of its oid', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    expect(rowsOf(wrapper)[1]!.text()).toContain('00000000…')

    wrapper.unmount()
  })

  it('links a case target to the case page and a player target to the player page', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    expect(rowsOf(wrapper)[0]!.get('a').attributes('href')).toBe('/cases/ABC123')
    expect(rowsOf(wrapper)[1]!.get('a').attributes('href')).toBe(`/players/${'cd'.repeat(32)}`)

    wrapper.unmount()
  })

  it('shows a section target as plain text with no link', async () => {
    const { mount } = setup({ auditLog: vi.fn(async () => ok({ items: [SECTION_UNLINK], nextCursor: null })) })
    const { wrapper } = await mount()

    expect(rowsOf(wrapper)[0]!.text()).toContain('network')
    expect(rowsOf(wrapper)[0]!.find('a').exists(), 'no link').toBe(false)

    wrapper.unmount()
  })

  it('shows the no-items state when there are no entries and no filter', async () => {
    const { mount } = setup({ auditLog: vi.fn(async () => ok({ items: [], nextCursor: null })) })
    const { wrapper } = await mount()

    expect(wrapper.get('[data-variant="no-items"]').text()).toContain('No audited actions yet')

    wrapper.unmount()
  })

  it('shows the failure with a retry and loads again on retry', async () => {
    const auditLog = vi.fn().mockResolvedValueOnce(fail('backend down', 0, 'NETWORK')).mockResolvedValueOnce(ok({ items: [REVOKE], nextCursor: null }))
    const { mount } = setup({ auditLog })
    const { wrapper } = await mount()

    expect(wrapper.get('[role="alert"]').text()).toContain('backend down')
    await wrapper.get('[data-testid="retry"]').trigger('click')
    await flushPromises()

    expect(rowsOf(wrapper)).toHaveLength(1)

    wrapper.unmount()
  })
})

describe('AuditLogPanel: paging', () => {
  it('appends the next page once, asking with the cursor of the last entry shown', async () => {
    const auditLog = vi
      .fn()
      .mockResolvedValueOnce(ok({ items: [REVOKE], nextCursor: 'c-1' }))
      .mockResolvedValueOnce(ok({ items: [PLAYER_KICK], nextCursor: null }))
    const { mount } = setup({ auditLog })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="audit-next"]').trigger('click')
    await flushPromises()

    expect(auditLog).toHaveBeenLastCalledWith({ limit: 50, cursor: 'c-1' })
    expect(rowsOf(wrapper)).toHaveLength(2)
    expect(rowsOf(wrapper)[1]!.text()).toContain('Kick player')

    wrapper.unmount()
  })

  it('shows no next control on the last page', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="audit-next"]').exists()).toBe(false)

    wrapper.unmount()
  })
})

describe('AuditLogPanel: filters', () => {
  it('filters by action and starts again at the first page', async () => {
    const auditLog = vi
      .fn()
      .mockResolvedValueOnce(ok({ items: [REVOKE], nextCursor: 'c-1' }))
      .mockResolvedValueOnce(ok({ items: [REVOKE], nextCursor: null }))
    const { mount } = setup({ auditLog })
    const { wrapper } = await mount()

    await chooseOption(wrapper.get('[data-testid="action"] [role="combobox"]'), 'punishment.revoke')
    await flushPromises()

    expect(auditLog).toHaveBeenLastCalledWith({ limit: 50, action: 'punishment.revoke' })

    wrapper.unmount()
  })

  it('filters by actor and by target', async () => {
    const auditLog = vi.fn(async () => ok({ items: [], nextCursor: null }))
    const { mount } = setup({ auditLog })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="actor"] input').setValue(ADMIN_OID)
    await flushPromises()
    expect(auditLog).toHaveBeenLastCalledWith({ limit: 50, actorOid: ADMIN_OID })

    await wrapper.get('[data-testid="target"] input').setValue('ABC123')
    await flushPromises()
    expect(auditLog).toHaveBeenLastCalledWith({ limit: 50, actorOid: ADMIN_OID, targetRef: 'ABC123' })

    wrapper.unmount()
  })

  it('shows the no-matches state when the filters hide every entry, and clears them', async () => {
    const auditLog = vi.fn(async (query?: { action?: string }) => (query?.action ? ok({ items: [], nextCursor: null }) : ok({ items: [REVOKE], nextCursor: null })))
    const { mount } = setup({ auditLog })
    const { wrapper } = await mount()

    await chooseOption(wrapper.get('[data-testid="action"] [role="combobox"]'), 'kick.mass')
    await flushPromises()
    expect(wrapper.find('[data-variant="no-matches"]').exists()).toBe(true)

    await wrapper.get('[data-testid="empty-clear-filters"]').trigger('click')
    await flushPromises()
    expect(rowsOf(wrapper)).toHaveLength(1)

    wrapper.unmount()
  })
})

describe('AuditLogPanel: entry detail', () => {
  it('opens the entry in a side sheet with its audit id, and offers no edit, delete or re-post control', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    await rowsOf(wrapper)[0]!.get('[data-testid="audit-open"]').trigger('click')
    await flushPromises()

    const sheet = wrapper.get('[data-testid="audit-detail"]')
    expect(sheet.text()).toContain(REVOKE.auditId)
    expect(sheet.text()).toContain('Mod Ann')
    expect(sheet.find('a[href="/cases/ABC123"]').exists(), 'link to the case').toBe(true)
    const labels = wrapper.findAll('button').map((button) => button.text())
    expect(labels.some((label) => /edit|delete|re-?post|revoke/i.test(label)), `buttons: ${labels.join(', ')}`).toBe(false)

    wrapper.unmount()
  })
})
