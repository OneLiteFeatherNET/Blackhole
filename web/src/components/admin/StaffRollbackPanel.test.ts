import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { ApiError, RollbackPreview, RollbackProgress } from '../../api/types'
import { mockApi, mountWithApp, ok } from '../../test/mount'
import { chooseOption, listOptions } from '../../test/select'
import StaffRollbackPanel from './StaffRollbackPanel.vue'

const OID = '00000000-0000-4000-8000-0000000000b2'
const PROXY = '00000000-0000-4000-8000-0000000000c3'
const JOB = '6f1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f'
const FROM = '2026-10-01T10:00'
const TO = '2026-10-02T10:00'

const preview: RollbackPreview = {
  jobId: JOB,
  state: 'PREVIEW',
  previewExpiresAt: Date.UTC(2026, 9, 29, 13, 0),
  revocable: 3,
  alreadyInactive: 1,
  players: 2,
  kicks: 1,
  byKind: { BAN: 2, MUTE: 1 },
  bySection: { chat: 2, game: 1 },
}

const progress = (state: string, items: Record<string, number> = {}): RollbackProgress => ({ jobId: JOB, state, items })

/** A timer the test fires by hand; nothing waits on a real clock. */
function manualTimer() {
  const entries: Array<{ run: () => void; cancelled: boolean; fired: boolean }> = []
  return {
    timer: {
      after(_ms: number, run: () => void) {
        const entry = { run, cancelled: false, fired: false }
        entries.push(entry)
        return () => {
          entry.cancelled = true
        }
      },
    },
    live: () => entries.filter((entry) => !entry.cancelled && !entry.fired).length,
    fire: () => {
      const next = entries.find((entry) => !entry.cancelled && !entry.fired)
      if (!next) throw new Error('no read is scheduled')
      next.fired = true
      next.run()
    },
  }
}

function setup(roles: string[] = ['ADMIN'], overrides: Record<string, unknown> = {}) {
  const clock = manualTimer()
  const api = {
    staffActors: vi.fn(async () =>
      ok([
        { oid: OID, displayName: 'Alex', kind: 'HUMAN', firstSeenAt: 1, lastSeenAt: 1 },
        { oid: PROXY, displayName: 'Proxy', kind: 'SERVICE', firstSeenAt: 1, lastSeenAt: 1 },
      ]),
    ),
    previewRollback: vi.fn(async () => ok(preview)),
    executeRollback: vi.fn(async () => ok({ jobId: JOB, state: 'RUNNING', repeated: false })),
    rollbackProgress: vi.fn(async () => ok(progress('RUNNING', { PENDING: 3 }))),
    cancelRollback: vi.fn(async () => ok({ jobId: JOB, state: 'CANCELLED' })),
    ...overrides,
  }
  return {
    api,
    clock,
    mount: () => mountWithApp(StaffRollbackPanel, { api: mockApi(api), roles, props: { timer: clock.timer } }),
  }
}

type Mounted = Awaited<ReturnType<ReturnType<typeof setup>['mount']>>['wrapper']

async function fillForm(wrapper: Mounted) {
  await chooseOption(wrapper.get('[data-testid="rollback-staff"] [role="combobox"]'), OID)
  await wrapper.get('[data-testid="rollback-from"] input').setValue(FROM)
  await wrapper.get('[data-testid="rollback-to"] input').setValue(TO)
  await flushPromises()
}

async function previewIt(wrapper: Mounted) {
  await fillForm(wrapper)
  await wrapper.get('[data-testid="rollback-preview"]').trigger('click')
  await flushPromises()
}

async function continueToConfirm(wrapper: Mounted) {
  await previewIt(wrapper)
  await wrapper.get('[data-testid="rollback-continue"]').trigger('click')
  await flushPromises()
}

async function typeReason(wrapper: Mounted, text: string) {
  await wrapper.get('[data-testid="rollback-reason"] textarea').setValue(text)
}

describe('StaffRollbackPanel gating', () => {
  it('is not rendered for a moderator, and no staff list is requested', async () => {
    const { api, mount } = setup(['MODERATOR'])
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="staff-rollback"]').exists(), 'no rollback screen').toBe(false)
    expect(api.staffActors, 'no request').not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('is not rendered for a viewer either', async () => {
    const { mount } = setup(['VIEWER'])
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="staff-rollback"]').exists()).toBe(false)
    wrapper.unmount()
  })
})

describe('StaffRollbackPanel preview', () => {
  it('keeps Preview disabled until a staff member and a window are chosen', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="rollback-preview"]').attributes('disabled'), 'nothing chosen').toBeDefined()
    await chooseOption(wrapper.get('[data-testid="rollback-staff"] [role="combobox"]'), OID)
    expect(wrapper.get('[data-testid="rollback-preview"]').attributes('disabled'), 'no window').toBeDefined()
    await wrapper.get('[data-testid="rollback-from"] input').setValue(FROM)
    await wrapper.get('[data-testid="rollback-to"] input').setValue(TO)
    await flushPromises()

    expect(wrapper.get('[data-testid="rollback-preview"]').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('offers only human staff members, never the proxy service principal', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    const options = await listOptions(wrapper.get('[data-testid="rollback-staff"] [role="combobox"]'))
    expect(options.map((option) => option.value)).toEqual([OID])
    wrapper.unmount()
  })

  it('asks the preview for the chosen staff member and the window as epoch milliseconds', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await previewIt(wrapper)

    expect(api.previewRollback).toHaveBeenCalledWith({ staffOid: OID, windowFrom: new Date(FROM).getTime(), windowTo: new Date(TO).getTime() })
    wrapper.unmount()
  })

  it('shows the counts of the preview and starts nothing', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await previewIt(wrapper)

    expect(wrapper.get('[data-testid="rollback-revocable"]').text(), 'punishments to revoke').toBe('3')
    expect(wrapper.get('[data-testid="rollback-already"]').text(), 'already inactive').toBe('1')
    expect(wrapper.get('[data-testid="rollback-players"]').text(), 'players').toBe('2')
    expect(wrapper.get('[data-testid="rollback-kicks"]').text(), 'kicks').toBe('1')
    expect(api.executeRollback, 'nothing started by the preview').not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('names the refusal of a selector that is not a human', async () => {
    const refusal: ApiError = { status: 400, message: 'backend text', kind: 'ROLLBACK_SELECTOR_INVALID' }
    const { mount } = setup(undefined, { previewRollback: vi.fn(async () => ({ ok: false, error: refusal })) })
    const { wrapper } = await mount()

    await previewIt(wrapper)

    expect(wrapper.get('[data-testid="error"]').text()).toBe('This cannot be rolled back. Choose a staff member or an unlinked in-game account.')
    wrapper.unmount()
  })

  it('names the refusal of a window that is too large and asks to narrow it', async () => {
    const refusal: ApiError = { status: 422, message: 'backend text', kind: 'ROLLBACK_TOO_LARGE' }
    const { mount } = setup(undefined, { previewRollback: vi.fn(async () => ({ ok: false, error: refusal })) })
    const { wrapper } = await mount()

    await previewIt(wrapper)

    expect(wrapper.get('[data-testid="error"]').text()).toBe('Too many punishments match. Narrow the window and preview again.')
    wrapper.unmount()
  })
})

describe('StaffRollbackPanel confirmation', () => {
  it('names the counts in the confirmation and leaves both options off', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    await continueToConfirm(wrapper)

    const counts = wrapper.get('[data-testid="rollback-confirm-counts"]').text()
    expect(counts).toContain('Revokes 3 punishments of 2 players')
    expect(counts).toContain('Kicks in the window: 1')
    expect((wrapper.get('[data-testid="option-marks"] input').element as HTMLInputElement).checked, 'marks').toBe(false)
    expect((wrapper.get('[data-testid="option-suspend"] input').element as HTMLInputElement).checked, 'suspend').toBe(false)
    wrapper.unmount()
  })

  it('keeps the start disabled until a reason is given', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    await continueToConfirm(wrapper)
    expect(wrapper.get('[data-testid="rollback-execute"]').attributes('disabled'), 'no reason').toBeDefined()
    await typeReason(wrapper, 'account taken over')
    expect(wrapper.get('[data-testid="rollback-execute"]').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('starts the rollback with the reason and the options the admin chose', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await continueToConfirm(wrapper)
    await typeReason(wrapper, '  account taken over ')
    await wrapper.get('[data-testid="option-marks"] input').setValue(true)
    await wrapper.get('[data-testid="option-suspend"] input').setValue(true)
    await wrapper.get('[data-testid="rollback-execute"]').trigger('click')
    await flushPromises()

    const [id, body, key] = api.executeRollback.mock.calls[0] as unknown as [string, Record<string, unknown>, string]
    expect(id).toBe(JOB)
    expect(body).toEqual({ reason: 'account taken over', suspendLink: true, includeMarks: true })
    expect(key).toMatch(/^[A-Za-z0-9-]{1,64}$/)
    wrapper.unmount()
  })

  it('reuses the same Idempotency-Key when the start is retried after a network failure', async () => {
    const network: ApiError = { status: 0, message: 'offline', kind: 'NETWORK' }
    const { api, mount } = setup(undefined, {
      executeRollback: vi.fn().mockResolvedValueOnce({ ok: false, error: network }).mockResolvedValueOnce(ok({ jobId: JOB, state: 'RUNNING', repeated: true })),
    })
    const { wrapper } = await mount()

    await continueToConfirm(wrapper)
    await typeReason(wrapper, 'account taken over')
    await wrapper.get('[data-testid="rollback-execute"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="error"]').text(), 'the failure is shown').toBe('offline')
    await wrapper.get('[data-testid="rollback-execute"]').trigger('click')
    await flushPromises()

    const firstKey = (api.executeRollback.mock.calls[0] as unknown as unknown[])[2]
    const retryKey = (api.executeRollback.mock.calls[1] as unknown as unknown[])[2]
    expect(retryKey, 'retry carries the key of the first attempt').toBe(firstKey)
    wrapper.unmount()
  })
})

describe('StaffRollbackPanel progress', () => {
  it('reads the job until it is COMPLETED and then stops reading', async () => {
    const { api, clock, mount } = setup(undefined, {
      rollbackProgress: vi
        .fn()
        .mockResolvedValueOnce(ok(progress('RUNNING', { PENDING: 2, REVOKED: 1 })))
        .mockResolvedValueOnce(ok(progress('COMPLETED', { REVOKED: 3 }))),
    })
    const { wrapper } = await mount()

    await continueToConfirm(wrapper)
    await typeReason(wrapper, 'account taken over')
    await wrapper.get('[data-testid="rollback-execute"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="rollback-state"]').text()).toBe('Running')

    clock.fire()
    await flushPromises()

    expect(wrapper.get('[data-testid="rollback-state"]').text()).toBe('Completed')
    expect(wrapper.get('[data-testid="rollback-done"]').text()).toBe('Done. The punishments are revoked.')
    expect(clock.live(), 'no further read is scheduled').toBe(0)
    expect(api.rollbackProgress).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('cancels a running rollback, shows it cancelled and stops reading', async () => {
    const { api, clock, mount } = setup()
    const { wrapper } = await mount()

    await continueToConfirm(wrapper)
    await typeReason(wrapper, 'account taken over')
    await wrapper.get('[data-testid="rollback-execute"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="rollback-cancel"]').trigger('click')
    await flushPromises()

    expect(api.cancelRollback).toHaveBeenCalledWith(JOB)
    expect(wrapper.get('[data-testid="rollback-state"]').text()).toBe('Cancelled')
    expect(clock.live(), 'the read is stopped').toBe(0)
    wrapper.unmount()
  })
})
