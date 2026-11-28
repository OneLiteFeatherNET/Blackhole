import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { ExemptionReview } from '../api/types'
import { fail, mockApi, mountWithApp, ok } from '../test/mount'
import ExemptionReviewDetailView from './ExemptionReviewDetailView.vue'

const ID = '5e8f2c10-0000-4000-8000-0000000000c1'
const OWNER = 'ab'.repeat(64)
const DETAIL = `/admin/exemption-reviews/${ID}`
const DECIDE = `/admin/exemption-reviews/${ID}/decide?action=apply`

const review: ExemptionReview = {
  id: ID,
  owner: OWNER,
  section: 'network',
  tier: 'PERMANENT',
  scoreAfter: 250,
  crossedAt: Date.UTC(2026, 9, 25, 12, 0, 0),
  state: 'OPEN',
}

function setup(overrides: { read?: () => unknown; apply?: () => unknown } = {}) {
  const api = {
    exemptionReview: vi.fn(overrides.read ?? (async () => ok(review))),
    exemption: vi.fn(async () => ok({ exempt: true, sources: ['PERMISSION'], sections: ['*'], rank: 'MODERATOR' })),
    applyExemptionReview: vi.fn(overrides.apply ?? (async () => ok({ ...review, state: 'APPLIED', justification: 'Checked' }))),
    dismissExemptionReview: vi.fn(async () => ok({ ...review, state: 'DISMISSED', justification: 'Checked' })),
  }
  const mount = (path: string) =>
    mountWithApp(ExemptionReviewDetailView, { api: mockApi(api), path, props: { id: ID }, roles: ['ADMIN', 'MODERATOR', 'VIEWER'] })
  return { api, mount }
}

describe('ExemptionReviewDetailView', () => {
  it('loads the review by the id in its address and shows the section, track and score', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount(DETAIL)

    expect(api.exemptionReview).toHaveBeenCalledWith(ID)
    expect(wrapper.text()).toContain('network')
    expect(wrapper.text()).toContain('PERMANENT')
    expect(wrapper.text()).toContain('250')
    wrapper.unmount()
  })

  it('shows the player by the short hash only, never the full hash', async () => {
    const { mount } = setup()
    const { wrapper } = await mount(DETAIL)

    expect(wrapper.find(`a[href="/players/${OWNER}"]`).exists(), 'link to the player').toBe(true)
    expect(wrapper.text(), 'short form only').not.toContain(OWNER)
    wrapper.unmount()
  })

  it('offers the copy-link action on the page', async () => {
    const { mount } = setup()
    const { wrapper } = await mount(DETAIL)

    expect(wrapper.find('[data-testid="copy-link"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('opens the decision by a routed click and sends nothing until it is confirmed', async () => {
    const { api, mount } = setup()
    const { wrapper, router } = await mount(DETAIL)

    await wrapper.get('[data-testid="apply-review"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('admin-exemption-review-decide')
    expect(router.currentRoute.value.query.action).toBe('apply')
    expect(api.applyExemptionReview, 'opening the dialog is not a decision').not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('opens the dismissal from the overflow menu by a routed click and sends nothing', async () => {
    const { api, mount } = setup()
    const { wrapper, router } = await mount(DETAIL)

    await wrapper.get('button[aria-haspopup="menu"]').trigger('click')
    const dismiss = wrapper.findAll('[role="menuitem"]').find((item) => item.text() === 'Dismiss')
    expect(dismiss, 'the dismissal is in the overflow menu').toBeDefined()
    await dismiss!.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('admin-exemption-review-decide')
    expect(router.currentRoute.value.query.action).toBe('dismiss')
    expect(api.dismissExemptionReview, 'opening the dialog is not a decision').not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('shows the decision dialog at the decision address, with the justification field', async () => {
    const { mount } = setup()
    const { wrapper } = await mount(DECIDE)

    expect(wrapper.find('[data-testid="justification"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('sends the decision once, with the justification, when it is confirmed', async () => {
    const { api, mount } = setup()
    const { wrapper, router } = await mount(DECIDE)

    await wrapper.get('[data-testid="justification"] textarea').setValue('Owner verified the report')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(api.applyExemptionReview).toHaveBeenCalledTimes(1)
    expect(api.applyExemptionReview).toHaveBeenCalledWith(ID, 'Owner verified the report')
    expect(router.currentRoute.value.name, 'the dialog closed after the decision').toBe('admin-exemption-review')
    wrapper.unmount()
  })

  it('closes the decision on Back without sending a decision', async () => {
    const { api, mount } = setup()
    const { wrapper, router } = await mount(DETAIL)
    await router.push(DECIDE)
    await flushPromises()

    await router.back()
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('admin-exemption-review')
    expect(wrapper.find('[data-testid="justification"]').exists(), 'the dialog is closed').toBe(false)
    expect(api.applyExemptionReview, 'no decision was sent').not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('closes the decision on Cancel without sending a decision', async () => {
    const { api, mount } = setup()
    const { wrapper, router } = await mount(DECIDE)

    await wrapper.get('[data-testid="cancel"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('admin-exemption-review')
    expect(api.applyExemptionReview).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('shows the not-found text and no dialog for an unknown review', async () => {
    const { api, mount } = setup({ read: async () => fail('No such review', 404, 'NOT_FOUND') })
    const { wrapper } = await mount(DECIDE)

    expect(wrapper.text()).toContain('There is no such review.')
    expect(wrapper.find('[data-testid="justification"]').exists(), 'no empty dialog').toBe(false)
    expect(api.applyExemptionReview).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('does not open a decision for a review that was decided already', async () => {
    const { mount } = setup({ read: async () => ok({ ...review, state: 'APPLIED', justification: 'Checked' }) })
    const { wrapper } = await mount(DECIDE)

    expect(wrapper.find('[data-testid="justification"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="apply-review"]').exists(), 'no decision is offered').toBe(false)
    wrapper.unmount()
  })
})
