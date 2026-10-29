import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { StaffTicketState } from '../../api/types'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import CaseLockControl from './CaseLockControl.vue'
import type { LockState, TicketView } from './tickets'

const REFERENCE = 'p-0001'

function ticket(phase: TicketView['phase'] = 'OPEN'): TicketView {
  return {
    ticketId: '3f2b8c1e-0000-4000-8000-0000000000f1',
    ticketNo: 12,
    appealId: '3f2b8c1e-0000-4000-8000-0000000000f2',
    openedAt: Date.UTC(2026, 6, 1, 12, 0),
    phase,
    transcriptComplete: false,
    protocolVersion: null,
    incompleteSince: null,
    channelLostEarly: false,
    protocolMissing: false,
  }
}

interface Setup {
  tickets?: TicketView[]
  assigneeName?: string | null
  leakFrozen?: boolean
  known?: LockState | null
  roles?: string[]
  freeze?: (reference: string, frozen: boolean) => Promise<unknown>
}

/** The freeze answers what the server would: the lock is set by staff, or cleared. */
function setup({ tickets = [ticket()], assigneeName = 'Ada', leakFrozen = false, known = null, roles = ['MODERATOR'], freeze }: Setup = {}) {
  const setStaffTicketFreeze = vi.fn(
    freeze ?? (async (_reference: string, frozen: boolean) => ok<StaffTicketState>({ frozen, freezeSource: frozen ? 'STAFF' : null })),
  )
  const mount = () =>
    mountWithApp(CaseLockControl, {
      api: mockApi({ setStaffTicketFreeze }),
      roles,
      props: { reference: REFERENCE, tickets, assigneeName, leakFrozen, known },
    })
  return { setStaffTicketFreeze, mount }
}

describe('CaseLockControl', () => {
  it('lets a moderator lock an open ticket, and then offers to unlock the staff lock', async () => {
    const { setStaffTicketFreeze, mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="lock-ticket"]').trigger('click')
    await flushPromises()

    expect(setStaffTicketFreeze).toHaveBeenCalledWith(REFERENCE, true)
    expect(wrapper.find('[data-testid="lock-ticket"]').exists(), 'a locked ticket is not locked again').toBe(false)
    expect(wrapper.find('[data-testid="unlock-ticket"]').exists(), 'a staff lock can be unlocked').toBe(true)
    expect(wrapper.get('[data-testid="lock-state"]').text()).toBe('Locked by staff')

    wrapper.unmount()
  })

  it('sends the unlock for a staff lock and offers the lock again afterwards', async () => {
    const { setStaffTicketFreeze, mount } = setup()
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="lock-ticket"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-testid="unlock-ticket"]').trigger('click')
    await flushPromises()

    expect(setStaffTicketFreeze).toHaveBeenLastCalledWith(REFERENCE, false)
    expect(wrapper.find('[data-testid="lock-ticket"]').exists(), 'an unlocked ticket can be locked').toBe(true)

    wrapper.unmount()
  })

  it('lets an administrator lock an open ticket', async () => {
    const { mount } = setup({ roles: ['ADMIN'] })
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="lock-ticket"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('shows a viewer no lock or unlock control', async () => {
    const { mount } = setup({ roles: ['VIEWER'] })
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="lock-ticket"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="unlock-ticket"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows a leak freeze as locked with no unlock and says the leak check set it', async () => {
    const { mount } = setup({ leakFrozen: true })
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="unlock-ticket"]').exists(), 'staff cannot lift a leak freeze').toBe(false)
    expect(wrapper.find('[data-testid="lock-ticket"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="lock-leak"]').text()).toContain('leak check')

    wrapper.unmount()
  })

  it('offers no lock once the appeal is decided', async () => {
    const { mount } = setup({ tickets: [ticket('CLOSING')] })
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="lock-ticket"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="lock-decided"]').text()).toBe('Decided')

    wrapper.unmount()
  })

  it('shows the server text of a refused lock and keeps the lock offer', async () => {
    const { mount } = setup({ freeze: async () => fail('the case has no open appeal ticket', 404, 'NOT_FOUND') })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="lock-ticket"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="lock-error"]').text()).toBe('the case has no open appeal ticket')
    expect(wrapper.find('[data-testid="lock-ticket"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('shows the lock the case already knows, without a "not known yet" text', async () => {
    const { mount } = setup({ known: { frozen: true, source: 'STAFF' } })
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="lock-state"]').text()).toBe('Locked by staff')
    expect(wrapper.text(), 'a known state is never reported as unknown').not.toContain('Not known yet')
    expect(wrapper.find('[data-testid="unlock-ticket"]').exists(), 'the known staff lock can be unlocked').toBe(true)

    wrapper.unmount()
  })

  it('follows a lock event from the timeline that arrives after the page loaded', async () => {
    const { mount } = setup({ known: { frozen: false, source: null } })
    const { wrapper } = await mount()
    expect(wrapper.get('[data-testid="lock-state"]').text()).toBe('Not locked')

    await wrapper.setProps({ known: { frozen: true, source: 'STAFF' } })

    expect(wrapper.get('[data-testid="lock-state"]').text()).toBe('Locked by staff')
    wrapper.unmount()
  })

  it('says the state is not known only when neither the case nor the timeline has one', async () => {
    const { mount } = setup({ known: null })
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="lock-state"]').text()).toBe('Not known yet. Locking or unlocking here shows the state.')
    wrapper.unmount()
  })

  it('says that no one claimed the case when there is no assignee', async () => {
    const { mount } = setup({ assigneeName: null })
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="lock-assignee"]').text()).toBe('Not claimed')

    wrapper.unmount()
  })
})
