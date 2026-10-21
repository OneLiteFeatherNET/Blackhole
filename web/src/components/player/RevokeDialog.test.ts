import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { RevokeResult } from '../../api/types'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import RevokeDialog from './RevokeDialog.vue'

const OWNER = 'ab'.repeat(64)

function mountDialog(revoke: ReturnType<typeof vi.fn>, props: Record<string, unknown> = {}) {
  return mountWithApp(RevokeDialog, {
    api: mockApi({ revoke }),
    props: { open: true, owner: OWNER, section: 'network', permanent: false, pardonScore: 700, ...props },
  })
}

describe('RevokeDialog', () => {
  it('explains that a temporary punishment moves to the history', async () => {
    const { wrapper } = await mountDialog(vi.fn())

    expect(wrapper.text()).toContain('The active punishment in network ends and moves to the history.')
    expect(wrapper.text()).not.toContain('pardon score')

    wrapper.unmount()
  })

  it('announces the score reset before revoking a permanent punishment', async () => {
    const { wrapper } = await mountDialog(vi.fn(), { permanent: true })

    expect(wrapper.get('[data-testid="reset-note"]').text()).toContain('reset to the pardon score 700')

    wrapper.unmount()
  })

  it('revokes the section and reports the reset score', async () => {
    const revoke = vi.fn(async () => ok({ section: 'network', scoreReset: 700 } as unknown as RevokeResult))
    const { wrapper } = await mountDialog(revoke, { permanent: true })

    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(revoke).toHaveBeenCalledWith(OWNER, 'network')
    expect(wrapper.emitted('done')).toEqual([['Revoked; the score in network is now 700.']])

    wrapper.unmount()
  })

  it('reports a plain revocation of a temporary punishment', async () => {
    const revoke = vi.fn(async () => ok({ section: 'network' } as unknown as RevokeResult))
    const { wrapper } = await mountDialog(revoke)

    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('done')).toEqual([['Revoked.']])

    wrapper.unmount()
  })

  it('stays open with the backend error when revoking fails', async () => {
    const revoke = vi.fn(async () => fail('the player has no active punishment in section network', 404, 'NOT_FOUND'))
    const { wrapper } = await mountDialog(revoke)

    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="error"]').text()).toContain('no active punishment')
    expect(wrapper.emitted('done')).toBeUndefined()

    wrapper.unmount()
  })

  it('emits close on cancel without revoking', async () => {
    const revoke = vi.fn()
    const { wrapper } = await mountDialog(revoke)

    await wrapper.get('[data-testid="cancel"]').trigger('click')

    expect(wrapper.emitted('close')).toHaveLength(1)
    expect(revoke).not.toHaveBeenCalled()

    wrapper.unmount()
  })
})
