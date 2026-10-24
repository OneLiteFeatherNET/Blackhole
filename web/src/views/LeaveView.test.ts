import { describe, expect, it, vi } from 'vitest'
import { encodeLeaveTarget } from '../components/case/chat/safeUrl'
import { mountWithApp } from '../test/mount'
import LeaveView from './LeaveView.vue'

async function mountAt(fragment: string) {
  return mountWithApp(LeaveView, { path: `/leave#${fragment}` })
}

describe('LeaveView: the interstitial for external links', () => {
  it('shows the full target as text and offers to continue to it in a new, unreferred tab', async () => {
    const target = 'https://example.org/some/path?x=1'
    const { wrapper } = await mountAt(encodeLeaveTarget(target))

    expect(wrapper.get('[data-testid="leave-target"]').text()).toBe(target)
    const cont = wrapper.get('[data-testid="leave-continue"]')
    expect(cont.attributes('href')).toBe(target)
    expect(cont.attributes('target')).toBe('_blank')
    expect(cont.attributes('rel')).toBe('noopener noreferrer nofollow ugc')
    expect(wrapper.text()).toContain('Blackhole does not control')
    wrapper.unmount()
  })

  it('offers no way on for a target that is not http(s), and says why', async () => {
    const { wrapper } = await mountAt(encodeLeaveTarget('javascript:alert(1)'))

    expect(wrapper.find('[data-testid="leave-continue"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="leave-error"]').text()).toContain('not a web address')
    wrapper.unmount()
  })

  it('offers no way on for a fragment that is not a target at all', async () => {
    const { wrapper } = await mountAt('%%%')

    expect(wrapper.find('[data-testid="leave-continue"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="leave-error"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('goes back without redirecting by itself', async () => {
    const back = vi.spyOn(window.history, 'back').mockImplementation(() => {})
    const { wrapper } = await mountAt(encodeLeaveTarget('https://example.org/'))

    await wrapper.get('[data-testid="leave-back"]').trigger('click')

    expect(back).toHaveBeenCalledTimes(1)
    back.mockRestore()
    wrapper.unmount()
  })
})
