import { describe, expect, it } from 'vitest'
import type { Punishment } from '../../api/types'
import { mountWithApp } from '../../test/mount'
import PunishmentHistory from './PunishmentHistory.vue'

const SOURCE = '11111111-2222-3333-4444-555555555555'

function entry(overrides: Partial<Punishment> = {}): Punishment {
  return { identifier: 'p-1', shortId: 'ABC123', source: SOURCE, verified: false, section: 'network', permanent: false, lifted: false, metaData: {}, ...overrides }
}

async function issuedBy(overrides: Partial<Punishment>) {
  const { wrapper } = await mountWithApp(PunishmentHistory, { props: { history: [entry(overrides)] } })
  const cell = wrapper.findAll('tbody tr td')[2]!.text()
  wrapper.unmount()
  return cell
}

describe('PunishmentHistory: issued by', () => {
  it('shows the staff member behind a linked in-game punishment', async () => {
    expect(await issuedBy({ actorName: 'Ada' })).toBe('Ada')
  })

  it('shows the Minecraft UUID when the account is not linked', async () => {
    expect(await issuedBy({ actorName: null })).toBe(SOURCE)
  })
})
