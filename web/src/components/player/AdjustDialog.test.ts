import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { EloAdjustResult, EloSectionScore } from '../../api/types'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import AdjustDialog from './AdjustDialog.vue'

const OWNER = 'ab'.repeat(64)

const network: EloSectionScore = {
  section: 'network',
  displayName: 'Network',
  enforcement: 'BAN',
  score: 500,
  baseScore: 1000,
  softThreshold: 700,
  permanentThreshold: 300,
  pardonScore: 700,
}

function mountDialog(adjustElo: ReturnType<typeof vi.fn>, props: Record<string, unknown> = {}) {
  return mountWithApp(AdjustDialog, {
    api: mockApi({ adjustElo }),
    props: { open: true, owner: OWNER, entry: network, hasActivePermanent: false, ...props },
  })
}

async function fill(wrapper: Awaited<ReturnType<typeof mountDialog>>['wrapper'], delta: string, note: string) {
  const [deltaInput, noteInput] = wrapper.findAll('input')
  await deltaInput!.setValue(delta)
  await noteInput!.setValue(note)
}

describe('AdjustDialog', () => {
  it('previews the resulting score while typing', async () => {
    const { wrapper } = await mountDialog(vi.fn())

    await fill(wrapper, '-100', 'cheating')

    expect(wrapper.get('[data-testid="preview"]').text()).toContain('Score in network: 500 to 400')
    expect(wrapper.find('[data-testid="permanent-warning"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('warns when the new score is below the permanent threshold', async () => {
    const { wrapper } = await mountDialog(vi.fn())

    await fill(wrapper, '-250', 'cheating')

    const warning = wrapper.get('[data-testid="permanent-warning"]')
    expect(warning.attributes('role')).toBe('alert')
    expect(warning.text()).toContain('250')
    expect(warning.text()).toContain('permanently in network')

    wrapper.unmount()
  })

  it('announces an automatic temporary punishment when the soft threshold is crossed', async () => {
    const { wrapper } = await mountDialog(vi.fn(), { entry: { ...network, score: 750 } })

    await fill(wrapper, '-100', 'spam')

    expect(wrapper.get('[data-testid="soft-note"]').text()).toContain('soft threshold 700')

    wrapper.unmount()
  })

  it('does not warn while a permanent punishment is already in force', async () => {
    const { wrapper } = await mountDialog(vi.fn(), { hasActivePermanent: true })

    await fill(wrapper, '-250', 'cheating')

    expect(wrapper.find('[data-testid="permanent-warning"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('requires a whole number other than zero and a note, and calls nothing otherwise', async () => {
    const adjustElo = vi.fn()
    const { wrapper } = await mountDialog(adjustElo)

    await fill(wrapper, '0', '  ')
    await wrapper.get('[data-testid="confirm"]').trigger('click')

    expect(wrapper.text()).toContain('Enter a whole number other than 0.')
    expect(wrapper.text()).toContain('A note is required.')
    expect(adjustElo).not.toHaveBeenCalled()

    await fill(wrapper, '1.5', 'x')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    expect(wrapper.text()).toContain('Enter a whole number other than 0.')

    wrapper.unmount()
  })

  it('adjusts the score with the trimmed note and reports the new score', async () => {
    const adjustElo = vi.fn(async () => ok({ section: 'network', scoreBefore: 500, scoreAfter: 400 } as unknown as EloAdjustResult))
    const { wrapper } = await mountDialog(adjustElo)
    await fill(wrapper, '-100', '  cheating  ')

    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(adjustElo).toHaveBeenCalledWith(OWNER, { section: 'network', delta: -100, note: 'cheating' })
    expect(wrapper.emitted('done')).toEqual([['Score adjusted to 400.']])

    wrapper.unmount()
  })

  it('reports an automatic permanent punishment caused by the change', async () => {
    const escalated = { section: 'network', scoreAfter: 250, escalation: { tier: 'PERMANENT' } } as unknown as EloAdjustResult
    const { wrapper } = await mountDialog(vi.fn(async () => ok(escalated)))
    await fill(wrapper, '-250', 'cheating')

    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('done')).toEqual([['Score adjusted to 250; the player is now permanently punished in network.']])

    wrapper.unmount()
  })

  it('stays open with the backend error when the change is rejected', async () => {
    const { wrapper } = await mountDialog(vi.fn(async () => fail('section is disabled', 409, 'CONFLICT')))
    await fill(wrapper, '-10', 'x')

    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="error"]').text()).toContain('section is disabled')
    expect(wrapper.emitted('done')).toBeUndefined()

    wrapper.unmount()
  })
})
