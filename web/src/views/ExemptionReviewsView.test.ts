import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { ExemptionReview } from '../api/types'
import { mockApi, mountWithApp, ok } from '../test/mount'
import ExemptionReviewsView from './ExemptionReviewsView.vue'

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

function setup(reviews: ExemptionReview[] = [review]) {
  const api = {
    exemption: vi.fn(async () => ok({ exempt: true, sources: ['PERMISSION'], sections: ['*'], rank: 'MODERATOR' })),
    exemptionReviews: vi.fn(async () => ok(reviews)),
    applyExemptionReview: vi.fn(async () => ok({ ...review, state: 'APPLIED', justification: 'Checked' })),
    dismissExemptionReview: vi.fn(async () => ok({ ...review, state: 'DISMISSED', justification: 'Checked' })),
  }
  const mount = (path = '/admin/exemption-reviews') =>
    mountWithApp(ExemptionReviewsView, { api: mockApi(api), path, roles: ['ADMIN', 'MODERATOR', 'VIEWER'] })
  return { api, mount }
}

describe('ExemptionReviewsView', () => {
  it('lists the open reviews with their section, track and score', async () => {
    const { mount } = setup()
    const { wrapper } = await mount('/admin/exemption-reviews?status=open')

    const row = wrapper.get('tbody tr')
    expect(row.text()).toContain('network')
    expect(row.text()).toContain('PERMANENT')
    expect(row.text()).toContain('250')
    wrapper.unmount()
  })

  it('says so when no review is open', async () => {
    const { mount } = setup([])
    const { wrapper } = await mount()

    expect(wrapper.text()).toContain('No open exemption reviews.')
    wrapper.unmount()
  })

  it('links each review to its own page by its id', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    expect(wrapper.find(`a[href="/admin/exemption-reviews/${review.id}"]`).exists()).toBe(true)
    wrapper.unmount()
  })

  it('opens the decision of a review by its address and sends nothing', async () => {
    const { api, mount } = setup()
    const { wrapper, router } = await mount()

    await wrapper.get('[data-testid="apply-review-1"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('admin-exemption-review-decide')
    expect(router.currentRoute.value.params.id).toBe(review.id)
    expect(api.applyExemptionReview, 'the click only navigates').not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('links each owner to the player page without showing the hash in full', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    expect(wrapper.find(`a[href="/players/${OWNER}"]`).exists(), 'link to the player').toBe(true)
    expect(wrapper.text(), 'short form only').not.toContain(OWNER)
    wrapper.unmount()
  })
})
