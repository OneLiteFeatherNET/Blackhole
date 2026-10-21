import { flushPromises } from '@vue/test-utils'
import { chooseOption } from '../test/select'
import { describe, expect, it, vi } from 'vitest'
import type { Appeal } from '../api/types'
import { fail, mockApi, mountWithApp, ok } from '../test/mount'
import AppealsView from './AppealsView.vue'

const APPELLANT = 'ab'.repeat(64)

function appeal(overrides: Partial<Appeal> = {}): Appeal {
  return {
    identifier: 'a-1',
    punishmentIdentifier: 'punishment-0001',
    appellantHash: APPELLANT,
    statement: 'It was not me',
    status: 'ELIGIBLE_PENDING_REVIEW',
    eligibilityCheckResult: {},
    createdAt: Date.UTC(2030, 0, 2),
    updatedAt: Date.UTC(2030, 0, 2),
    metaData: {},
    ...overrides,
  } as Appeal
}

function setup(overrides: Record<string, unknown> = {}) {
  const api = {
    appeals: vi.fn(async () => ok([appeal(), appeal({ identifier: 'a-2', status: 'DENIED', statement: 'Old' })])),
    reviewAppeal: vi.fn(async () => ok(appeal({ status: 'GRANTED_FULL_LIFT' }))),
    ...overrides,
  }
  return { api, mount: () => mountWithApp(AppealsView, { api: mockApi(api), path: '/appeals' }) }
}

type Wrapper = Awaited<ReturnType<ReturnType<typeof setup>['mount']>>['wrapper']

const dialogField = (wrapper: Wrapper, label: string) => {
  const found = wrapper.findAll('[role="dialog"] label').find((l) => l.text().startsWith(label))
  if (!found) throw new Error(`no field labelled ${label}`)
  return wrapper.get(`[role="dialog"] [id="${found.attributes('for')}"]`)
}

describe('AppealsView: queue', () => {
  it('lists the pending appeals and hides decided ones by default', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    const rows = wrapper.findAll('tbody tr')
    expect(rows).toHaveLength(1)
    expect(rows[0]!.text()).toContain('It was not me')
    expect(rows[0]!.text()).toContain('Awaiting review')
    expect(rows[0]!.text()).toContain('punishme')

    wrapper.unmount()
  })

  it('shows decided appeals when asked to, without a decide action', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="filter-all"]').trigger('click')

    expect(wrapper.findAll('tbody tr')).toHaveLength(2)
    expect(wrapper.findAll('tbody tr')[1]!.text()).toContain('Denied')
    expect(wrapper.find('[data-testid="decide-a-2"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('says so when nothing is pending', async () => {
    const { mount } = setup({ appeals: vi.fn(async () => ok([appeal({ status: 'DENIED' })])) })
    const { wrapper } = await mount()

    expect(wrapper.text()).toContain('No pending appeals.')

    wrapper.unmount()
  })

  it('shows the error with a retry when loading fails', async () => {
    const appeals = vi.fn().mockResolvedValueOnce(fail('backend down', 0, 'NETWORK')).mockResolvedValueOnce(ok([appeal()]))
    const { mount } = setup({ appeals })
    const { wrapper } = await mount()

    expect(wrapper.get('[role="alert"]').text()).toContain('backend down')
    await wrapper.get('[data-testid="retry"]').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('tbody tr')).toHaveLength(1)

    wrapper.unmount()
  })
})

describe('AppealsView: deciding', () => {
  it('grants a full lift, which takes the appeal out of the pending queue', async () => {
    const appeals = vi
      .fn()
      .mockResolvedValueOnce(ok([appeal()]))
      .mockResolvedValueOnce(ok([appeal({ status: 'GRANTED_FULL_LIFT' })]))
    const { api, mount } = setup({ appeals })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="decide-a-1"]').trigger('click')
    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'GRANTED_FULL_LIFT')
    await dialogField(wrapper, 'Note').setValue('first offence')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(api.reviewAppeal).toHaveBeenCalledWith('a-1', { decision: 'GRANTED_FULL_LIFT', note: 'first offence', newExpirationAt: undefined })
    expect(wrapper.find('tbody tr').exists(), 'the appeal left the pending queue').toBe(false)
    expect(wrapper.get('[role="status"]').text()).toContain('Appeal decided.')

    wrapper.unmount()
  })

  it('denies an appeal', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="decide-a-1"]').trigger('click')
    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'DENIED')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(api.reviewAppeal).toHaveBeenCalledWith('a-1', { decision: 'DENIED', note: undefined, newExpirationAt: undefined })

    wrapper.unmount()
  })

  it('asks for the new end when shortening and sends it as epoch millis', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="decide-a-1"]').trigger('click')
    expect(wrapper.find('input[type="datetime-local"]').exists(), 'no end date before shortening is chosen').toBe(false)

    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'GRANTED_DURATION_REDUCTION')
    await dialogField(wrapper, 'New end').setValue('2099-01-02T12:30')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(api.reviewAppeal).toHaveBeenCalledWith('a-1', {
      decision: 'GRANTED_DURATION_REDUCTION',
      note: undefined,
      newExpirationAt: new Date('2099-01-02T12:30').getTime(),
    })

    wrapper.unmount()
  })

  it('requires the new end for a shortening and calls nothing without it', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="decide-a-1"]').trigger('click')
    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'GRANTED_DURATION_REDUCTION')

    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Choose when the punishment should end.')
    expect(api.reviewAppeal).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('explains a self-review refusal', async () => {
    const reviewAppeal = vi.fn(async () => fail('HTTP 403', 403, 'UNKNOWN'))
    const { mount } = setup({ reviewAppeal })
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="decide-a-1"]').trigger('click')
    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'DENIED')

    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="error"]').text()).toContain('issued the punishment')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('shows the backend message for a rejected decision', async () => {
    const reviewAppeal = vi.fn(async () => fail('A SEVERE punishment cannot be fully lifted', 400, 'VALIDATION'))
    const { mount } = setup({ reviewAppeal })
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="decide-a-1"]').trigger('click')
    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'GRANTED_FULL_LIFT')

    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="error"]').text()).toContain('SEVERE punishment cannot be fully lifted')

    wrapper.unmount()
  })

  it('explains that the appeal is no longer awaiting a decision', async () => {
    const reviewAppeal = vi.fn(async () => fail('HTTP 409', 409, 'CONFLICT'))
    const { mount } = setup({ reviewAppeal })
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="decide-a-1"]').trigger('click')
    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'DENIED')

    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="error"]').text()).toContain('no longer awaiting a decision')

    wrapper.unmount()
  })
})
