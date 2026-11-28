import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { RollbackPreview, RollbackProgress } from '../../api/types'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import { chooseOption, listOptions } from '../../test/select'
import StaffRollbackPanel from './StaffRollbackPanel.vue'

const OID = '00000000-0000-4000-8000-0000000000b2'
const PROXY = '00000000-0000-4000-8000-0000000000c3'
const JOB = '6f1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f'
const FROM = '2026-10-01T10:00'
const TO = '2026-10-02T10:00'
const ADDRESS_NEW = '/admin/rollback/new'
const ADDRESS_JOB = `/admin/rollback/${JOB}`

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

function setup(path: string, roles: string[] = ['ADMIN'], overrides: Record<string, unknown> = {}) {
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
    rollbackProgress: vi.fn(async () => ok(progress('PREVIEW', { PENDING: 3 }))),
    cancelRollback: vi.fn(async () => ok({ jobId: JOB, state: 'CANCELLED' })),
    ...overrides,
  }
  return {
    api,
    clock,
    mount: () => mountWithApp(StaffRollbackPanel, { api: mockApi(api), roles, path, props: { timer: clock.timer } }),
  }
}

async function fillWindow(wrapper: VueWrapper) {
  await wrapper.get('[data-testid="rollback-from"] input').setValue(FROM)
  await wrapper.get('[data-testid="rollback-to"] input').setValue(TO)
}

describe('StaffRollbackPanel gating', () => {
  it.each([['MODERATOR'], ['VIEWER']])('is not rendered for a %s, and no staff list is requested', async (role) => {
    const { api, mount } = setup(ADDRESS_NEW, [role])
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="staff-rollback"]').exists()).toBe(false)
    expect(api.staffActors).not.toHaveBeenCalled()

    wrapper.unmount()
  })
})

describe('StaffRollbackPanel preview dialog at /admin/rollback/new', () => {
  it('reads the staff list once the dialog is open, and offers only human staff members', async () => {
    const { api, mount } = setup(ADDRESS_NEW)
    const { wrapper } = await mount()

    const options = await listOptions(wrapper.get('[data-testid="rollback-staff"] [role="combobox"]'))
    expect(options.map((option) => option.value)).toContain(OID)
    expect(options.map((option) => option.value)).not.toContain(PROXY)
    expect(api.staffActors).toHaveBeenCalledTimes(1)

    wrapper.unmount()
  })

  it('keeps Preview disabled until a staff member and a window are chosen', async () => {
    const { mount } = setup(ADDRESS_NEW)
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="rollback-preview"]').attributes('disabled')).toBeDefined()
    await chooseOption(wrapper.get('[data-testid="rollback-staff"] [role="combobox"]'), OID)
    await fillWindow(wrapper)
    expect(wrapper.get('[data-testid="rollback-preview"]').attributes('disabled')).toBeUndefined()

    wrapper.unmount()
  })

  it('asks the preview for the chosen staff member and the window as epoch milliseconds', async () => {
    const { api, mount } = setup(ADDRESS_NEW)
    const { wrapper } = await mount()

    await chooseOption(wrapper.get('[data-testid="rollback-staff"] [role="combobox"]'), OID)
    await fillWindow(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(api.previewRollback).toHaveBeenCalledWith({ staffOid: OID, windowFrom: new Date(FROM).getTime(), windowTo: new Date(TO).getTime() })

    wrapper.unmount()
  })

  it('shows the counts of the preview and starts nothing', async () => {
    const { api, mount } = setup(ADDRESS_NEW)
    const { wrapper } = await mount()

    await chooseOption(wrapper.get('[data-testid="rollback-staff"] [role="combobox"]'), OID)
    await fillWindow(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[data-testid="rollback-preview-step"]').text()).toContain('Nothing has changed yet')
    expect(wrapper.get('[data-testid="rollback-preview-step"]').text()).toContain('3')
    expect(api.executeRollback).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('opens the job of the preview by its id, and still starts nothing', async () => {
    const { api, mount } = setup(ADDRESS_NEW)
    const { wrapper, router } = await mount()

    await chooseOption(wrapper.get('[data-testid="rollback-staff"] [role="combobox"]'), OID)
    await fillWindow(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.get('[data-testid="rollback-open-job"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('admin-rollback-job')
    expect(router.currentRoute.value.params.id).toBe(JOB)
    expect(api.executeRollback).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('names the refusal of a selector that is not a human', async () => {
    const refusal = { ok: false as const, error: { status: 400, message: 'backend text', kind: 'ROLLBACK_SELECTOR_INVALID' as const } }
    const { mount } = setup(ADDRESS_NEW, ['ADMIN'], { previewRollback: vi.fn(async () => refusal) })
    const { wrapper } = await mount()

    await chooseOption(wrapper.get('[data-testid="rollback-staff"] [role="combobox"]'), OID)
    await fillWindow(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('This cannot be rolled back')

    wrapper.unmount()
  })
})

describe('StaffRollbackPanel job at /admin/rollback/:id', () => {
  it('reads a preview once, shows that it waits for the start, and offers the start and the cancel as their own addresses', async () => {
    const { api, clock, mount } = setup(ADDRESS_JOB)
    const { wrapper } = await mount()

    expect(api.rollbackProgress).toHaveBeenCalledTimes(1)
    expect(clock.live(), 'nothing is polled for a preview').toBe(0)
    expect(wrapper.get('[data-testid="rollback-state"]').text()).toContain('Preview')
    expect(wrapper.get('[data-testid="rollback-execute-link"]').attributes('href')).toBe(`${ADDRESS_JOB}/execute`)
    expect(wrapper.get('[data-testid="rollback-cancel-link"]').attributes('href')).toBe(`${ADDRESS_JOB}/cancel`)
    expect(api.executeRollback).not.toHaveBeenCalled()
    expect(api.cancelRollback).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('reads a running job until it is COMPLETED and then stops reading', async () => {
    const rollbackProgress = vi
      .fn()
      .mockResolvedValueOnce(ok(progress('RUNNING', { PENDING: 2, REVOKED: 1 })))
      .mockResolvedValueOnce(ok(progress('COMPLETED', { REVOKED: 3 })))
    const { clock, mount } = setup(ADDRESS_JOB, ['ADMIN'], { rollbackProgress })
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="rollback-running"]').exists()).toBe(true)
    clock.fire()
    await flushPromises()

    expect(wrapper.get('[data-testid="rollback-done"]').text()).toContain('Done')
    expect(clock.live()).toBe(0)
    expect(wrapper.find('[data-testid="rollback-cancel-link"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows the not-found text for an unknown job, and no job actions', async () => {
    const { mount } = setup(ADDRESS_JOB, ['ADMIN'], {
      rollbackProgress: vi.fn(async () => ({ ok: false, error: { status: 404, message: 'no such rollback', kind: 'NOT_FOUND' } })),
    })
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="rollback-job-missing"]').text()).toContain('No rollback with this id')
    expect(wrapper.find('[data-testid="rollback-execute-link"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows a failed read with a retry that reads again', async () => {
    const rollbackProgress = vi
      .fn()
      .mockResolvedValueOnce({ ok: false, error: { status: 0, message: 'offline', kind: 'NETWORK' } })
      .mockResolvedValueOnce(ok(progress('PREVIEW', { PENDING: 3 })))
    const { mount } = setup(ADDRESS_JOB, ['ADMIN'], { rollbackProgress })
    const { wrapper } = await mount()

    expect(wrapper.get('[role="alert"]').text()).toContain('offline')
    await wrapper.get('[data-testid="rollback-retry"]').trigger('click')
    await flushPromises()

    expect(rollbackProgress).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)

    wrapper.unmount()
  })
})

describe('StaffRollbackPanel start at /admin/rollback/:id/execute', () => {
  const EXECUTE = `${ADDRESS_JOB}/execute`

  it('shows the frozen count and sends nothing until the start is confirmed', async () => {
    const { api, mount } = setup(EXECUTE)
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="rollback-execute-counts"]').text()).toContain('3 punishments')
    expect(api.executeRollback).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('keeps the start disabled until a reason is given', async () => {
    const { mount } = setup(EXECUTE)
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="rollback-execute"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-testid="rollback-reason"] textarea').setValue('  Ban wave went wrong  ')
    expect(wrapper.get('[data-testid="rollback-execute"]').attributes('disabled')).toBeUndefined()

    wrapper.unmount()
  })

  it('starts the rollback with the reason and the options chosen, then returns to the job', async () => {
    const { api, mount } = setup(EXECUTE)
    const { wrapper, router } = await mount()

    await wrapper.get('[data-testid="rollback-reason"] textarea').setValue('Ban wave went wrong')
    await wrapper.get<HTMLInputElement>('[data-testid="option-suspend"] input').setValue(true)
    await wrapper.get('[data-testid="rollback-execute"]').trigger('click')
    await flushPromises()

    expect(api.executeRollback).toHaveBeenCalledWith(JOB, { reason: 'Ban wave went wrong', suspendLink: true, includeMarks: false }, expect.any(String))
    expect(router.currentRoute.value.name).toBe('admin-rollback-job')

    wrapper.unmount()
  })

  it('reuses the same Idempotency-Key when the start is retried after a network failure', async () => {
    const executeRollback = vi.fn().mockResolvedValueOnce(fail('network down', 0, 'NETWORK')).mockResolvedValueOnce(ok({ jobId: JOB, state: 'RUNNING', repeated: false }))
    const { mount } = setup(EXECUTE, ['ADMIN'], { executeRollback })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="rollback-reason"] textarea').setValue('Ban wave went wrong')
    await wrapper.get('[data-testid="rollback-execute"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="rollback-execute"]').trigger('click')
    await flushPromises()

    expect(executeRollback).toHaveBeenCalledTimes(2)
    expect(executeRollback.mock.calls[1]![2]).toBe(executeRollback.mock.calls[0]![2])

    wrapper.unmount()
  })

  it('shows the not-found text for an unknown job and no start control', async () => {
    const { mount } = setup(EXECUTE, ['ADMIN'], {
      rollbackProgress: vi.fn(async () => ({ ok: false, error: { status: 404, message: 'no such rollback', kind: 'NOT_FOUND' } })),
    })
    const { wrapper } = await mount()

    expect(wrapper.text()).toContain('This item does not exist or was removed.')
    expect(wrapper.find('[data-testid="rollback-execute"]').exists()).toBe(false)

    wrapper.unmount()
  })
})

describe('StaffRollbackPanel cancel at /admin/rollback/:id/cancel', () => {
  const CANCEL = `${ADDRESS_JOB}/cancel`

  it('sends nothing when the address opens and cancels only on confirm', async () => {
    const { api, mount } = setup(CANCEL)
    const { wrapper, router } = await mount()

    expect(api.cancelRollback).not.toHaveBeenCalled()
    await wrapper.get('[data-testid="rollback-cancel-confirm"]').trigger('click')
    await flushPromises()

    expect(api.cancelRollback).toHaveBeenCalledWith(JOB)
    expect(router.currentRoute.value.name).toBe('admin-rollback-job')

    wrapper.unmount()
  })

  it('says that a rollback which already ended cannot be cancelled', async () => {
    const { mount } = setup(CANCEL, ['ADMIN'], {
      cancelRollback: vi.fn(async () => ({ ok: false, error: { status: 409, message: 'done', kind: 'CONFLICT' } })),
    })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="rollback-cancel-confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('already ended')

    wrapper.unmount()
  })
})
