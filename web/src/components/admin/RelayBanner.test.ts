import { flushPromises } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { ProxyCapabilities } from '../../api/types'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import RelayBanner from './RelayBanner.vue'

function capabilities(proxies: ProxyCapabilities['proxies']): ProxyCapabilities {
  return { proxies }
}

async function mountWith(result: ReturnType<typeof ok> | ReturnType<typeof fail>) {
  return mountWithApp(RelayBanner, { api: mockApi({ proxyCapabilities: async () => result }) })
}

describe('RelayBanner', () => {
  it('is hidden while every proxy reports SignedVelocity', async () => {
    const { wrapper } = await mountWith(ok(capabilities([{ proxyId: 'Proxy-1', state: 'PRESENT', version: '1.5.0' }])))
    await flushPromises()

    expect(wrapper.find('[data-testid="relay-banner"]').exists(), 'no banner').toBe(false)

    wrapper.unmount()
  })

  it('names the proxy without SignedVelocity and says muted signed chat is blocked by disconnect', async () => {
    const { wrapper } = await mountWith(ok(capabilities([
      { proxyId: 'Proxy-1', state: 'PRESENT', version: '1.5.0' },
      { proxyId: 'Proxy-2', state: 'ABSENT' },
    ])))
    await flushPromises()

    const banner = wrapper.find('[data-testid="relay-banner"]')
    expect(banner.exists(), 'banner shown').toBe(true)
    expect(banner.text(), 'proxy named').toContain('Proxy-2')
    expect(banner.text(), 'consequence named').toContain('disconnect')
    expect(banner.text(), 'healthy proxy not named').not.toContain('Proxy-1')

    wrapper.unmount()
  })

  it('shows a proxy that has not reported as unknown, never as present', async () => {
    const { wrapper } = await mountWith(ok(capabilities([{ proxyId: 'Proxy-3', state: 'UNKNOWN' }])))
    await flushPromises()

    const banner = wrapper.find('[data-testid="relay-banner"]')
    expect(banner.text(), 'unknown is said').toContain('unknown')
    expect(banner.text(), 'proxy named').toContain('Proxy-3')

    wrapper.unmount()
  })

  it('stays hidden when the state cannot be read', async () => {
    const { wrapper } = await mountWith(fail('boom', 503, 'UNKNOWN'))
    await flushPromises()

    expect(wrapper.find('[data-testid="relay-banner"]').exists(), 'no false warning').toBe(false)

    wrapper.unmount()
  })
})
