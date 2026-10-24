import { describe, expect, it } from 'vitest'
import { evidenceItem } from '../../test/evidence'
import { mockApi, mountWithApp, ok } from '../../test/mount'
import EvidenceItem from './EvidenceItem.vue'

const PUNISHMENT = 'p-0001'
type Overrides = Parameters<typeof evidenceItem>[0]

/** A chat capture of the punishment, with the given changes. */
function captured(changes: Record<string, unknown> = {}) {
  return evidenceItem({
    kind: 'CHAT_LOG',
    punishmentIdentifier: PUNISHMENT,
    payload: { lines: [{ timestamp: 1000, speaker: 'Steve', text: 'hi' }] },
    ...changes,
  } as Overrides)
}

function mount(item: ReturnType<typeof evidenceItem>, roles?: string[]) {
  const api = mockApi({ evidenceShares: async () => ok([]), evidenceContent: async () => ok(new Blob(['x'])) })
  return mountWithApp(EvidenceItem, { api, roles, props: { item } })
}

describe('EvidenceItem: sharing with the player', () => {
  it('offers the share action to a moderator for a valid chat capture', async () => {
    const { wrapper } = await mount(captured(), ['MODERATOR'])

    expect(wrapper.find('[data-testid="share"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('does not render the share action for a viewer, who reads only', async () => {
    const { wrapper } = await mount(captured(), ['VIEWER'])

    expect(wrapper.find('[data-testid="share"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('never offers to share an anticheat record', async () => {
    const item = evidenceItem({ kind: 'ANTICHEAT', punishmentIdentifier: PUNISHMENT, payload: { check: 'Speed' } })
    const { wrapper } = await mount(item, ['ADMIN'])

    expect(wrapper.find('[data-testid="share"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('never offers to share a lifted item', async () => {
    const { wrapper } = await mount(captured({ status: 'LIFTED' }), ['MODERATOR'])

    expect(wrapper.find('[data-testid="share"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('labels a player-supplied file and never offers to share it back', async () => {
    const { wrapper } = await mount(captured({ provenance: 'PLAYER' }), ['MODERATOR'])

    expect(wrapper.get('[data-testid="provenance"]').text()).toBe('Player-supplied, unverified')
    expect(wrapper.find('[data-testid="share"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('shows no provenance label for a team item', async () => {
    const { wrapper } = await mount(captured({ provenance: 'STAFF' }), ['MODERATOR'])

    expect(wrapper.find('[data-testid="provenance"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
