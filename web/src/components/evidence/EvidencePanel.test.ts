import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { evidenceItem, evidencePage } from '../../test/evidence'
import { mockApi, mountWithApp, ok } from '../../test/mount'
import { chooseOption } from '../../test/select'
import EvidencePanel from './EvidencePanel.vue'

const PUNISHMENT = 'ab0e5a9e-1111-4222-8333-444455556666'

const valid = evidenceItem({ kind: 'ANTICHEAT', payload: { check: 'Speed' } })
const lifted = evidenceItem({ kind: 'LINK', reference: 'https://example.org/x', status: 'LIFTED' })
const revoked = evidenceItem({ kind: 'REPLAY', reference: 'https://replay.example/1', status: 'REVOKED' })

/** Answers like the backend: lifted and revoked items only with includeInactive. */
function backend(count = 1) {
  const evidence = vi.fn(async (_punishment: string, query?: { includeInactive?: boolean; page?: number }) =>
    ok(evidencePage(query?.includeInactive ? [valid, lifted, revoked] : [valid])),
  )
  const evidenceCount = vi.fn(async () => ok(count))
  const deleteEvidence = vi.fn(async () => ok(1))
  const api = mockApi({ evidence, evidenceCount, deleteEvidence, evidenceContentUrl: (id: string) => `/api/evidence/${id}/content` })
  return { api, evidence, evidenceCount, deleteEvidence }
}

describe('EvidencePanel', () => {
  it('lists the evidence of the punishment', async () => {
    const { api, evidence } = backend()
    const { wrapper } = await mountWithApp(EvidencePanel, { api, props: { punishment: PUNISHMENT } })

    expect(evidence).toHaveBeenCalledWith(PUNISHMENT, { includeInactive: false, page: 0 })
    expect(wrapper.findAll('[data-testid="evidence-item"]')).toHaveLength(1)

    wrapper.unmount()
  })

  it('does not ask for lifted evidence by default', async () => {
    const { api, evidence } = backend()
    const { wrapper } = await mountWithApp(EvidencePanel, { api, props: { punishment: PUNISHMENT } })

    expect(evidence.mock.calls.every(([, query]) => query?.includeInactive === false), 'every request omits lifted evidence').toBe(true)
    expect(wrapper.text()).not.toContain('Punishment lifted by appeal')

    wrapper.unmount()
  })

  it('starts with the toggle off', async () => {
    const { api } = backend()
    const { wrapper } = await mountWithApp(EvidencePanel, { api, props: { punishment: PUNISHMENT } })

    expect(wrapper.get<HTMLInputElement>('[data-testid="show-lifted"] input').element.checked).toBe(false)

    wrapper.unmount()
  })

  it('asks for lifted evidence when the toggle is switched on', async () => {
    const { api, evidence } = backend()
    const { wrapper } = await mountWithApp(EvidencePanel, { api, props: { punishment: PUNISHMENT } })

    await wrapper.get('[data-testid="show-lifted"] input').setValue(true)
    await flushPromises()

    expect(evidence).toHaveBeenLastCalledWith(PUNISHMENT, { includeInactive: true, page: 0 })
    expect(wrapper.findAll('[data-testid="evidence-item"]')).toHaveLength(3)

    wrapper.unmount()
  })

  it('labels lifted and revoked items', async () => {
    const { api } = backend()
    const { wrapper } = await mountWithApp(EvidencePanel, { api, props: { punishment: PUNISHMENT } })

    await wrapper.get('[data-testid="show-lifted"] input').setValue(true)
    await flushPromises()

    const labels = wrapper.findAll('[data-testid="status"]').map((status) => status.text())
    expect(labels).toEqual(['Punishment lifted by appeal', 'Punishment revoked'])

    wrapper.unmount()
  })

  it('counts only valid evidence, also with lifted items shown', async () => {
    const { api, evidenceCount } = backend(1)
    const { wrapper } = await mountWithApp(EvidencePanel, { api, props: { punishment: PUNISHMENT } })

    await wrapper.get('[data-testid="show-lifted"] input').setValue(true)
    await flushPromises()

    expect(wrapper.get('[data-testid="count"]').text()).toBe('1 valid')
    expect(wrapper.findAll('[data-testid="evidence-item"]')).toHaveLength(3)
    expect(evidenceCount).toHaveBeenCalledWith(PUNISHMENT)

    wrapper.unmount()
  })

  it('hides lifted evidence again when the toggle is switched off', async () => {
    const { api } = backend()
    const { wrapper } = await mountWithApp(EvidencePanel, { api, props: { punishment: PUNISHMENT } })

    await wrapper.get('[data-testid="show-lifted"] input').setValue(true)
    await flushPromises()
    await wrapper.get('[data-testid="show-lifted"] input').setValue(false)
    await flushPromises()

    expect(wrapper.findAll('[data-testid="evidence-item"]')).toHaveLength(1)

    wrapper.unmount()
  })

  it('does not remember the toggle across mounts or in browser storage', async () => {
    window.localStorage.clear()
    const first = await mountWithApp(EvidencePanel, { api: backend().api, props: { punishment: PUNISHMENT } })
    await first.wrapper.get('[data-testid="show-lifted"] input').setValue(true)
    first.wrapper.unmount()

    const second = await mountWithApp(EvidencePanel, { api: backend().api, props: { punishment: PUNISHMENT } })

    expect(second.wrapper.get<HTMLInputElement>('[data-testid="show-lifted"] input').element.checked).toBe(false)
    expect(window.localStorage.length, 'local storage entries').toBe(0)

    second.wrapper.unmount()
  })

  it('offers upload and link adding to staff', async () => {
    const { api } = backend()
    const { wrapper } = await mountWithApp(EvidencePanel, { api, props: { punishment: PUNISHMENT } })

    expect(wrapper.find('[data-testid="dropzone"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="open-add-link"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('offers neither upload nor link adding when read-only, but still deleting', async () => {
    const { api } = backend()
    const { wrapper } = await mountWithApp(EvidencePanel, { api, props: { punishment: PUNISHMENT, readonly: true } })

    expect(wrapper.find('[data-testid="dropzone"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="open-add-link"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="delete"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('deletes an item after the dialog is confirmed with a reason and reloads', async () => {
    const { api, deleteEvidence, evidence, evidenceCount } = backend()
    const { wrapper } = await mountWithApp(EvidencePanel, { api, props: { punishment: PUNISHMENT } })

    await wrapper.get('[data-testid="delete"]').trigger('click')
    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'WRONG_EVIDENCE')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(deleteEvidence).toHaveBeenCalledWith(valid.identifier, { reason: 'WRONG_EVIDENCE', note: '' })
    expect(evidence).toHaveBeenCalledTimes(2)
    expect(evidenceCount).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('Evidence deleted.')

    wrapper.unmount()
  })

  it('requests the next page', async () => {
    const evidence = vi.fn(async (_p: string, query?: { page?: number }) =>
      ok(evidencePage([valid], { page: query?.page ?? 0, totalPages: 2 })),
    )
    const api = mockApi({ evidence, evidenceCount: async () => ok(1), evidenceContentUrl: () => '' })
    const { wrapper } = await mountWithApp(EvidencePanel, { api, props: { punishment: PUNISHMENT } })

    await wrapper.get('[data-testid="next"]').trigger('click')
    await flushPromises()

    expect(evidence).toHaveBeenLastCalledWith(PUNISHMENT, { includeInactive: false, page: 1 })

    wrapper.unmount()
  })

  it('states that opening evidence is audited and that the toggle is no access control', async () => {
    const { api } = backend()
    const { wrapper } = await mountWithApp(EvidencePanel, { api, props: { punishment: PUNISHMENT } })

    expect(wrapper.text()).toContain('recorded in the audit trail')
    expect(wrapper.text()).toContain('not an access restriction')

    wrapper.unmount()
  })
})
