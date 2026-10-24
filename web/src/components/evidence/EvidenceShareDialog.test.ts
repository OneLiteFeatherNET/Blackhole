import { flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { EvidenceShare } from '../../api/types'
import { evidenceItem } from '../../test/evidence'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import { chooseOption } from '../../test/select'
import EvidenceShareDialog from './EvidenceShareDialog.vue'

const PUNISHMENT = 'p-0001'
const EVIDENCE = '5c1d2e3f-0000-4000-8000-000000000001'
const SHARE = '5c1d2e3f-0000-4000-8000-000000000002'
const OTHER_SHARE = '5c1d2e3f-0000-4000-8000-000000000003'

function share(overrides: Partial<EvidenceShare> = {}): EvidenceShare {
  return {
    shareId: SHARE,
    evidenceId: EVIDENCE,
    evidenceKind: 'CHAT_LOG',
    mode: 'EXCERPT',
    state: 'ACTIVE',
    sharedAt: 1,
    sharedBy: '00000000-0000-4000-8000-0000000000a1',
    repeated: false,
    lineCount: 2,
    ...overrides,
  }
}

/** A chat capture of `count` lines, speakers Steve and Alex alternating. */
function capture(count = 3) {
  const lines = Array.from({ length: count }, (_, index) => ({
    timestamp: 1_000 * (index + 1),
    speaker: index % 2 === 0 ? 'Steve' : 'Alex',
    text: `line ${index}`,
  }))
  return evidenceItem({ identifier: EVIDENCE, kind: 'CHAT_LOG', punishmentIdentifier: PUNISHMENT, payload: { lines } })
}

const SCREENSHOT = evidenceItem({
  identifier: EVIDENCE,
  kind: 'FILE',
  punishmentIdentifier: PUNISHMENT,
  file: { name: 'shot.png', contentType: 'image/png', size: 4 } as never,
})

/** Answers the way the backend does for the lines given; the preview repeats the selection with the appellant as You. */
function backend(overrides: Record<string, unknown> = {}) {
  const api = {
    previewEvidenceShare: vi.fn(async (_reference: string, request: { selection?: number[] | null }) =>
      ok({
        mode: 'EXCERPT',
        lines: (request.selection ?? []).map((index) => ({ speaker: index === 0 ? 'You' : 'Player 2', offsetSeconds: index, text: `line ${index}` })),
      }),
    ),
    shareEvidence: vi.fn(async () => ok(share())),
    evidenceShares: vi.fn(async () => ok([])),
    revokeEvidenceShare: vi.fn(async () => ok(share({ state: 'REVOKED', revokedAt: 2, revokeReason: 'PRIVACY' }))),
    evidenceContent: vi.fn(async () => ok(new Blob(['png'], { type: 'image/png' }))),
    ...overrides,
  }
  return { api, client: mockApi(api) }
}

async function mountDialog(options: { item?: ReturnType<typeof capture>; roles?: string[]; api?: ReturnType<typeof backend> } = {}) {
  const backendApi = options.api ?? backend()
  const mounted = await mountWithApp(EvidenceShareDialog, {
    api: backendApi.client,
    roles: options.roles,
    props: { open: true, item: options.item ?? capture(), reference: PUNISHMENT },
  })
  return { ...mounted, api: backendApi.api }
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('EvidenceShareDialog: chat captures', () => {
  it('lists every line of the capture with a checkbox each', async () => {
    const { wrapper } = await mountDialog({ item: capture(3) })

    expect(wrapper.findAll('[data-testid^="share-line-"]')).toHaveLength(3)
    wrapper.unmount()
  })

  it('allows at most twenty lines and disables the rest', async () => {
    const { wrapper } = await mountDialog({ item: capture(25) })

    for (let index = 0; index < 20; index++) await wrapper.get(`[data-testid="share-line-${index}"]`).setValue(true)
    await flushPromises()

    expect(wrapper.get('[data-testid="share-line-20"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="share-limit"]').text()).toContain('20')
    wrapper.unmount()
  })

  it('shows the preview the backend returns for the selection and the chosen appealing speaker', async () => {
    const { wrapper, api } = await mountDialog()

    await wrapper.get('[data-testid="share-line-1"]').setValue(true)
    await wrapper.get('[data-testid="share-line-0"]').setValue(true)
    await chooseOption(wrapper.get('[data-testid="share-speaker"] [role="combobox"]'), 'Steve')
    await flushPromises()

    expect(api.previewEvidenceShare).toHaveBeenLastCalledWith(PUNISHMENT, { evidenceId: EVIDENCE, selection: [0, 1], appellantSpeaker: 'Steve' })
    expect(wrapper.get('[data-testid="share-preview"]').text()).toContain('line 1')
    expect(wrapper.get('[data-testid="share-preview"]').text()).toContain('You')
    wrapper.unmount()
  })

  it('keeps the share button disabled until the minimised confirmation is ticked', async () => {
    const { wrapper } = await mountDialog()
    await wrapper.get('[data-testid="share-line-0"]').setValue(true)
    await chooseOption(wrapper.get('[data-testid="share-speaker"] [role="combobox"]'), 'Steve')
    await flushPromises()

    expect(wrapper.get('[data-testid="share-submit"]').attributes('disabled')).toBeDefined()

    await wrapper.get('[data-testid="share-confirm"] input').setValue(true)
    await flushPromises()

    expect(wrapper.get('[data-testid="share-submit"]').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('needs an appealing speaker before it can share', async () => {
    const { wrapper } = await mountDialog()
    await wrapper.get('[data-testid="share-line-0"]').setValue(true)
    await wrapper.get('[data-testid="share-confirm"] input').setValue(true)
    await flushPromises()

    expect(wrapper.get('[data-testid="share-submit"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('shares the selection with the speaker and the confirmation, then reports done', async () => {
    const { wrapper, api } = await mountDialog()
    await wrapper.get('[data-testid="share-line-1"]').setValue(true)
    await wrapper.get('[data-testid="share-line-0"]').setValue(true)
    await chooseOption(wrapper.get('[data-testid="share-speaker"] [role="combobox"]'), 'Steve')
    await wrapper.get('[data-testid="share-confirm"] input').setValue(true)
    await flushPromises()

    await wrapper.get('[data-testid="share-submit"]').trigger('click')
    await flushPromises()

    expect(api.shareEvidence).toHaveBeenCalledWith(PUNISHMENT, {
      evidenceId: EVIDENCE,
      selection: [0, 1],
      appellantSpeaker: 'Steve',
      confirmedMinimised: true,
    })
    expect(wrapper.emitted('done')).toHaveLength(1)
    wrapper.unmount()
  })

  it('shows the server answer when the share is refused, and keeps the dialog open', async () => {
    const api = backend({ shareEvidence: vi.fn(async () => fail('Line 2 holds an IP address', 422, 'VALIDATION')) })
    const { wrapper } = await mountDialog({ api })
    await wrapper.get('[data-testid="share-line-0"]').setValue(true)
    await chooseOption(wrapper.get('[data-testid="share-speaker"] [role="combobox"]'), 'Steve')
    await wrapper.get('[data-testid="share-confirm"] input').setValue(true)
    await flushPromises()

    await wrapper.get('[data-testid="share-submit"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="share-error"]').text()).toBe('Line 2 holds an IP address')
    expect(wrapper.emitted('done')).toBeUndefined()
    wrapper.unmount()
  })
})

describe('EvidenceShareDialog: screenshots', () => {
  it('shows the image with the stronger confirmation and shares it without lines', async () => {
    // jsdom has no object URLs; the dialog only needs one to show the image.
    Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:shot'), revokeObjectURL: vi.fn() })
    const { wrapper, api } = await mountDialog({ item: SCREENSHOT })

    expect(wrapper.get('[data-testid="share-image"]').attributes('src')).toBe('blob:shot')
    expect(wrapper.text()).toContain("may show other players' names")
    expect(wrapper.findAll('[data-testid^="share-line-"]')).toHaveLength(0)

    await wrapper.get('[data-testid="share-confirm"] input').setValue(true)
    await flushPromises()
    await wrapper.get('[data-testid="share-submit"]').trigger('click')
    await flushPromises()

    expect(api.shareEvidence).toHaveBeenCalledWith(PUNISHMENT, { evidenceId: EVIDENCE, confirmedMinimised: true })
    wrapper.unmount()
  })
})

describe('EvidenceShareDialog: revoking', () => {
  it('lists the active shares of this item and asks for a reason before it revokes one', async () => {
    const api = backend({
      evidenceShares: vi.fn(async () => ok([share(), share({ shareId: OTHER_SHARE, evidenceId: 'other', state: 'REVOKED' })])),
    })
    const { wrapper } = await mountDialog({ api })
    await flushPromises()

    expect(wrapper.findAll('[data-testid="share-row"]')).toHaveLength(1)
    await wrapper.get(`[data-testid="share-revoke-${SHARE}"]`).trigger('click')
    await flushPromises()

    expect(wrapper.text(), 'the warning says what revoking cannot do').toContain('already seen')
    await chooseOption(wrapper.get('[data-testid="revoke-share-reason"] [role="combobox"]'), 'PRIVACY')
    await wrapper.get('[data-testid="revoke-share-confirm"]').trigger('click')
    await flushPromises()

    expect(api.api.revokeEvidenceShare).toHaveBeenCalledWith(PUNISHMENT, SHARE, 'PRIVACY')
    wrapper.unmount()
  })
})
