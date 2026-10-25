import { describe, expect, it } from 'vitest'
import type { KickStatus } from '../../api/types'
import { mountWithApp } from '../../test/mount'
import KickStatusChip from './KickStatusChip.vue'

describe('KickStatusChip', () => {
  it.each([
    ['DELIVERED', 'Delivered'],
    ['NOT_ON_TARGET', 'Not on that server'],
    ['NOT_ONLINE', 'Not online'],
    ['NO_PROXY_ANSWER', 'No proxy answered'],
  ] as Array<[KickStatus, string]>)('shows %s as "%s"', async (status, label) => {
    const { wrapper } = await mountWithApp(KickStatusChip, { props: { status } })

    expect(wrapper.get('[data-testid="kick-status"]').text()).toBe(label)
    wrapper.unmount()
  })
})
