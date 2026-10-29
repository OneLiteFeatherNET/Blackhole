import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { ExemptionReview } from '../../api/types'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import ExemptionDecisionDialog from './ExemptionDecisionDialog.vue'

const OWNER = 'ab'.repeat(64)

const review: ExemptionReview = {
  id: 'review-1',
  owner: OWNER,
  section: 'network',
  tier: 'PERMANENT',
  scoreAfter: 250,
  crossedAt: Date.UTC(2026, 9, 25, 12, 0, 0),
  state: 'OPEN',
}

function decided(state: string): ExemptionReview {
  return { ...review, state, justification: 'Checked' }
}

function setup(overrides: Record<string, unknown> = {}) {
  const api = {
    applyExemptionReview: vi.fn(async () => ok(decided('APPLIED'))),
    dismissExemptionReview: vi.fn(async () => ok(decided('DISMISSED'))),
    ...overrides,
  }
  const mount = (action: 'apply' | 'dismiss') =>
    mountWithApp(ExemptionDecisionDialog, { api: mockApi(api), props: { open: true, review, action } })
  return { api, mount }
}

async function confirm(wrapper: Awaited<ReturnType<ReturnType<typeof setup>['mount']>>['wrapper']) {
  await wrapper.get('[data-testid="confirm"]').trigger('click')
  await flushPromises()
}

describe('ExemptionDecisionDialog justification', () => {
  it('requires a justification for an apply and sends nothing while it is empty', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount('apply')

    await confirm(wrapper)

    expect(api.applyExemptionReview, 'no decision without a justification').not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Enter a justification.')
    wrapper.unmount()
  })

  it('requires a justification for a dismiss and sends nothing while it is empty', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount('dismiss')

    await confirm(wrapper)

    expect(api.dismissExemptionReview, 'no decision without a justification').not.toHaveBeenCalled()
    wrapper.unmount()
  })
})

describe('ExemptionDecisionDialog actions', () => {
  it('applies the review with the justification and reports the review as decided', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount('apply')
    await wrapper.get('[data-testid="justification"] textarea').setValue('  Owner verified the report  ')

    await confirm(wrapper)

    expect(api.applyExemptionReview).toHaveBeenCalledWith('review-1', 'Owner verified the report')
    expect(api.dismissExemptionReview).not.toHaveBeenCalled()
    expect(wrapper.emitted('done'), 'reported as applied').toEqual([['Review applied.']])
    wrapper.unmount()
  })

  it('dismisses the review with the justification and creates no punishment', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount('dismiss')
    await wrapper.get('[data-testid="justification"] textarea').setValue('Score change was a false positive')

    await confirm(wrapper)

    expect(api.dismissExemptionReview).toHaveBeenCalledWith('review-1', 'Score change was a false positive')
    expect(api.applyExemptionReview).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('keeps the dialog open with the backend message when the decision fails', async () => {
    const { mount } = setup({ applyExemptionReview: vi.fn(async () => fail('review was decided already', 409, 'CONFLICT')) })
    const { wrapper } = await mount('apply')
    await wrapper.get('[data-testid="justification"] textarea').setValue('Owner verified the report')

    await confirm(wrapper)

    expect(wrapper.get('[data-testid="error"]').text()).toBe('review was decided already')
    expect(wrapper.emitted('done'), 'not reported as decided').toBeUndefined()
    wrapper.unmount()
  })

  it('disables the decision while it is being sent, so a second click sends nothing', async () => {
    let finish: (value: ReturnType<typeof ok>) => void = () => {}
    const pending = new Promise<ReturnType<typeof ok>>((resolve) => {
      finish = resolve
    })
    const { api, mount } = setup({ applyExemptionReview: vi.fn(() => pending) })
    const { wrapper } = await mount('apply')
    await wrapper.get('[data-testid="justification"] textarea').setValue('Owner verified the report')

    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="confirm"]').attributes('disabled'), 'disabled while sending').toBeDefined()
    expect(api.applyExemptionReview).toHaveBeenCalledTimes(1)

    finish(ok(decided('APPLIED')))
    await flushPromises()
    wrapper.unmount()
  })
})
