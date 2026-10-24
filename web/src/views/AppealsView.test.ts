import { flushPromises } from '@vue/test-utils'
import { chooseOption } from '../test/select'
import { describe, expect, it, vi } from 'vitest'
import type { Appeal } from '../api/types'
import { timelineItem, timelinePage } from '../test/cases'
import { evidenceItem, evidencePage } from '../test/evidence'
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
    caseTimeline: vi.fn(async () => ok(timelinePage([]))),
    evidence: vi.fn(async () => ok(evidencePage([evidenceItem({ kind: 'ANTICHEAT', payload: { check: 'Speed' } })]))),
    evidenceCount: vi.fn(async () => ok(1)),
    evidenceContent: async () => ok(new Blob(['x'], { type: 'image/png' })),
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
    expect(wrapper.findAll('[role="status"]').map((node) => node.text()).join(' ')).toContain('Appeal decided.')

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

describe('AppealsView: evidence of the appealed punishment', () => {
  it('lists the evidence of the appealed punishment in the decision dialog', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="decide-a-1"]').trigger('click')
    await flushPromises()

    expect(api.evidence).toHaveBeenCalledWith('punishment-0001', { includeInactive: false, page: 0 })
    expect(wrapper.get('[role="dialog"] [data-testid="evidence-item"]').text()).toContain('Speed')

    wrapper.unmount()
  })

  it('offers no upload or link adding to a reviewer, but deleting and the lifted toggle', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="decide-a-1"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="dropzone"]').exists(), 'drop zone').toBe(false)
    expect(wrapper.find('[data-testid="open-add-link"]').exists(), 'add link').toBe(false)
    expect(wrapper.find('[data-testid="delete"]').exists(), 'delete').toBe(true)
    expect(wrapper.find('[data-testid="show-lifted"]').exists(), 'lifted toggle').toBe(true)

    wrapper.unmount()
  })

  it('keeps the decision dialog open when Escape closes the delete dialog above it', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="decide-a-1"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="delete"]').trigger('click')
    const dialogs = wrapper.findAll('[role="dialog"]')
    await dialogs[dialogs.length - 1]!.trigger('keydown', { key: 'Escape' })

    expect(wrapper.findAll('[role="dialog"]')).toHaveLength(1)
    expect(wrapper.text()).toContain('Decide appeal')

    wrapper.unmount()
  })
})

describe('AppealsView: unanswered player messages', () => {
  const player = { trust: 'PLAYER' as const }
  const staff = { oid: '00000000-0000-4000-8000-0000000000b2', name: 'Ada', trust: 'VERIFIED' as const }
  const newest = (actor: typeof player | typeof staff) => ok(timelinePage([timelineItem({ type: 'message.added', actor })]))

  it('marks a pending appeal whose newest message is from the player, and no other', async () => {
    const caseTimeline = vi.fn(async (reference: string) => (reference === 'punishment-0001' ? newest(player) : newest(staff)))
    const { mount } = setup({
      appeals: vi.fn(async () => ok([appeal(), appeal({ identifier: 'a-3', punishmentIdentifier: 'punishment-0003' })])),
      caseTimeline,
    })
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="unanswered-a-1"]').text()).toBe('Unanswered')
    expect(wrapper.find('[data-testid="unanswered-a-3"]').exists()).toBe(false)
    expect(caseTimeline).toHaveBeenCalledWith('punishment-0001', { types: ['message.added'], order: 'desc', size: 1 })

    wrapper.unmount()
  })

  it('reads nothing for decided appeals', async () => {
    const { api, mount } = setup({ appeals: vi.fn(async () => ok([appeal({ status: 'DENIED' })])) })
    const { wrapper } = await mount()

    expect(api.caseTimeline).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('shows no marker when the conversation has no message or cannot be read', async () => {
    const caseTimeline = vi.fn().mockResolvedValueOnce(ok(timelinePage([]))).mockResolvedValueOnce(fail('down', 0, 'NETWORK'))
    const { mount } = setup({
      appeals: vi.fn(async () => ok([appeal(), appeal({ identifier: 'a-3', punishmentIdentifier: 'punishment-0003' })])),
      caseTimeline,
    })
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid^="unanswered-a"]').exists()).toBe(false)
    expect(wrapper.findAll('tbody tr')).toHaveLength(2)

    wrapper.unmount()
  })

  it('announces how many appeals wait for an answer in a polite live region', async () => {
    const { mount } = setup({ caseTimeline: vi.fn(async () => newest(player)) })
    const { wrapper } = await mount()

    const live = wrapper.get('[data-testid="unanswered-status"]')
    expect(live.attributes('aria-live')).toBe('polite')
    expect(live.text()).toBe('1 appeal with an unanswered player message')

    wrapper.unmount()
  })

  it('links every row to the case conversation', async () => {
    const { mount } = setup()
    const { wrapper, router } = await mount()

    const link = wrapper.get('[data-testid="case-a-1"]')
    expect(link.attributes('href')).toBe(router.resolve({ name: 'case', params: { reference: 'punishment-0001' } }).href)
    expect(link.text()).toBe('Open case')

    wrapper.unmount()
  })
})

describe('AppealsView: message to the player', () => {
  async function decide(wrapper: Wrapper, message: string) {
    await wrapper.get('[data-testid="decide-a-1"]').trigger('click')
    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'DENIED')
    await wrapper.get('[data-testid="player-message"]').setValue(message)
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()
  }

  it('sends the message next to the internal note', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="decide-a-1"]').trigger('click')
    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'DENIED')
    await dialogField(wrapper, 'Note').setValue('internal reason')
    await wrapper.get('[data-testid="player-message"]').setValue('  We looked again.  ')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(api.reviewAppeal).toHaveBeenCalledWith('a-1', {
      decision: 'DENIED',
      note: 'internal reason',
      newExpirationAt: undefined,
      playerMessage: 'We looked again.',
    })

    wrapper.unmount()
  })

  it('leaves the message out when the field is blank', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await decide(wrapper, '   ')

    expect(api.reviewAppeal).toHaveBeenCalledWith('a-1', expect.objectContaining({ playerMessage: undefined }))

    wrapper.unmount()
  })

  it('counts characters and refuses more than 2000 without deciding', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await decide(wrapper, 'x'.repeat(2001))

    expect(wrapper.get('[data-testid="player-message-counter"]').text()).toBe('2001 / 2000')
    expect(wrapper.get('[data-testid="player-message-hint"]').text()).toBe('A message has at most 2000 characters.')
    expect(wrapper.get('[data-testid="player-message-hint"]').attributes('role')).toBe('alert')
    expect(api.reviewAppeal).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('shows the server message when the player message is rejected', async () => {
    const reviewAppeal = vi.fn(async () => fail('a message must not contain a Ban ID', 400, 'VALIDATION'))
    const { mount } = setup({ reviewAppeal })
    const { wrapper } = await mount()

    await decide(wrapper, 'ABCD2345')

    expect(wrapper.get('[data-testid="error"]').text()).toContain('must not contain a Ban ID')

    wrapper.unmount()
  })
})
