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

function setup(reviews: ExemptionReview[][]) {
  // Each read answers the next list; the decision reads the list again afterwards.
  const queue = [...reviews]
  const api = {
    exemption: vi.fn(async () => ok({ exempt: true, sources: ['PERMISSION'], sections: ['*'], rank: 'MODERATOR' })),
    exemptionReviews: vi.fn(async () => ok(queue.shift() ?? [])),
    applyExemptionReview: vi.fn(async () => ok({ ...review, state: 'APPLIED', justification: 'Checked' })),
    dismissExemptionReview: vi.fn(async () => ok({ ...review, state: 'DISMISSED', justification: 'Checked' })),
  }
  return { api, mount: () => mountWithApp(ExemptionReviewsView, { api: mockApi(api), path: '/admin/exemption-reviews', roles: ['ADMIN', 'MODERATOR', 'VIEWER'] }) }
}

async function decide(wrapper: Awaited<ReturnType<ReturnType<typeof setup>['mount']>>['wrapper'], action: 'apply' | 'dismiss', justification: string) {
  await wrapper.get(`[data-testid="${action}-review-1"]`).trigger('click')
  await flushPromises()
  await wrapper.get('[data-testid="justification"] textarea').setValue(justification)
  await wrapper.get('[data-testid="confirm"]').trigger('click')
  await flushPromises()
}

describe('ExemptionReviewsView', () => {
  it('lists the open reviews with their section, track and score', async () => {
    const { mount } = setup([[review]])
    const { wrapper } = await mount()

    const row = wrapper.get('tbody tr')
    expect(row.text()).toContain('network')
    expect(row.text()).toContain('PERMANENT')
    expect(row.text()).toContain('250')
    wrapper.unmount()
  })

  it('says so when no review is open', async () => {
    const { mount } = setup([[]])
    const { wrapper } = await mount()

    expect(wrapper.text()).toContain('No open exemption reviews.')
    wrapper.unmount()
  })

  it('applies a review with a justification and removes it from the open list', async () => {
    const { api, mount } = setup([[review], []])
    const { wrapper } = await mount()

    await decide(wrapper, 'apply', 'Owner verified the report')

    expect(api.applyExemptionReview).toHaveBeenCalledWith('review-1', 'Owner verified the report')
    expect(wrapper.find('[data-testid="apply-review-1"]').exists(), 'the review left the list').toBe(false)
    expect(wrapper.text()).toContain('Review applied.')
    wrapper.unmount()
  })

  it('dismisses a review with a justification and removes it from the open list', async () => {
    const { api, mount } = setup([[review], []])
    const { wrapper } = await mount()

    await decide(wrapper, 'dismiss', 'Score change was a false positive')

    expect(api.dismissExemptionReview).toHaveBeenCalledWith('review-1', 'Score change was a false positive')
    expect(api.applyExemptionReview, 'no punishment is applied').not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="dismiss-review-1"]').exists(), 'the review left the list').toBe(false)
    wrapper.unmount()
  })

  it('links each owner to the player page without showing the hash in full', async () => {
    const { mount } = setup([[review]])
    const { wrapper } = await mount()

    expect(wrapper.find(`a[href="/players/${OWNER}"]`).exists(), 'link to the player').toBe(true)
    expect(wrapper.text(), 'short form only').not.toContain(OWNER)
    wrapper.unmount()
  })
})
