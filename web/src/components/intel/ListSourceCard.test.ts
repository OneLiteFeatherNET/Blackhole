import { describe, expect, it } from 'vitest'
import { formatDateTime } from '../../format'
import { mountWithApp } from '../../test/mount'
import ListSourceCard from './ListSourceCard.vue'
import { LIST_SOURCES } from './listSources'

const vpnSource = LIST_SOURCES[0]!
const LAST_OK = Date.UTC(2030, 0, 2, 10, 30)

function mountCard(props: Record<string, unknown>) {
  return mountWithApp(ListSourceCard, { props: { source: vpnSource, status: null, lastSuccessAt: null, ...props } })
}

describe('ListSourceCard', () => {
  it('renders the X4BNet attribution and its licence', async () => {
    const { wrapper } = await mountCard({})

    expect(wrapper.get('[data-testid="source-attribution"]').text()).toBe(
      'X4BNet lists_vpn, MIT, copyright 2024 X4B (Mathew Heard), https://github.com/X4BNet/lists_vpn',
    )
    expect(wrapper.get('[data-testid="source-licence"]').text()).toContain('MIT')

    wrapper.unmount()
  })

  it.each([
    ['FRESH', 'Fresh'],
    ['STALE', 'Stale'],
    ['FAILED', 'Failed'],
  ] as const)('maps the refresh status %s to the label "%s"', async (status, label) => {
    const { wrapper } = await mountCard({ status, lastSuccessAt: LAST_OK })

    expect(wrapper.get('[data-testid="source-status"]').text()).toBe(label)

    wrapper.unmount()
  })

  it('shows the time of the last successful refresh when the last refresh failed', async () => {
    const { wrapper } = await mountCard({ status: 'FAILED', lastSuccessAt: LAST_OK })

    expect(wrapper.get('[data-testid="source-last-ok"]').text()).toContain(formatDateTime(LAST_OK, 'en'))

    wrapper.unmount()
  })

  it('says the status is not reported when the backend gives none', async () => {
    const { wrapper } = await mountCard({ status: null })

    expect(wrapper.get('[data-testid="source-status"]').text()).toBe('Status not reported')

    wrapper.unmount()
  })
})
