import { flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CaseProtocolVersion } from '../../api/types'
import { caseResponse, timelineItem, timelinePage } from '../../test/cases'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import CaseView from '../../views/CaseView.vue'
import ProtocolSection from './ProtocolSection.vue'

const APPEAL = '3f2b8c1e-0000-4000-8000-0000000000a2'
const OTHER_APPEAL = '3f2b8c1e-0000-4000-8000-0000000000a3'
const EVIDENCE_1 = '3f2b8c1e-0000-4000-8000-0000000000e1'
const EVIDENCE_2 = '3f2b8c1e-0000-4000-8000-0000000000e2'

function version(overrides: Partial<CaseProtocolVersion> = {}): CaseProtocolVersion {
  return { appealId: APPEAL, version: 2, evidenceId: EVIDENCE_2, current: true, entryCount: 5, sourceDigest: 'abc', createdAt: Date.UTC(2026, 6, 2, 9, 0), ...overrides }
}

function stubDownload() {
  const created: Blob[] = []
  const clicked: Array<{ download: string; href: string }> = []
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: (blob: Blob) => (created.push(blob), 'blob:test'), revokeObjectURL: () => undefined }))
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
    clicked.push({ download: this.download, href: this.href })
  })
  return { created, clicked }
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

function setup(extra: Record<string, unknown> = {}) {
  const api = {
    caseProtocols: vi.fn(async () => ok([version(), version({ version: 1, evidenceId: EVIDENCE_1, current: false })])),
    caseDecidedAppeals: vi.fn(async () => ok([APPEAL])),
    openCaseProtocol: vi.fn(async () => ok({ appealId: APPEAL, version: 2, evidenceId: EVIDENCE_2, text: 'Player: <b>hello</b>\nStaff: hi' })),
    downloadCaseProtocol: vi.fn(async () => ok({ format: 'text' as const, content: 'plain', mediaType: 'text/plain' })),
    ...extra,
  }
  return { api, mount: () => mountWithApp(ProtocolSection, { api: mockApi(api), props: { reference: 'p-0001', shortId: '#AB12CD' } }) }
}

describe('ProtocolSection: versions', () => {
  it('lists the versions and marks the current one and the superseded one in words', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    const rows = wrapper.findAll('[data-testid="protocol-version"]')
    expect(rows).toHaveLength(2)
    expect(rows[0]!.text()).toContain('Version 2')
    expect(rows[0]!.get('[data-testid="protocol-state"]').text()).toBe('current')
    expect(rows[1]!.get('[data-testid="protocol-state"]').text()).toBe('superseded')
    expect(rows[1]!.text()).toContain('Replaced by a newer version')

    wrapper.unmount()
  })

  it('says so when no appeal has a protocol and none is waiting', async () => {
    const { mount } = setup({ caseProtocols: vi.fn(async () => ok([])), caseDecidedAppeals: vi.fn(async () => ok([]))})
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="protocol-none"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="protocol-pending"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows a notice while a decided appeal has no protocol yet', async () => {
    const { mount } = setup({ caseProtocols: vi.fn(async () => ok([])) })
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="protocol-pending"]').text()).toContain('no protocol yet')
    expect(wrapper.find('[data-testid="protocol-none"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows the notice when only another appeal has a protocol', async () => {
    const { mount } = setup({ caseDecidedAppeals: vi.fn(async () => ok([APPEAL, OTHER_APPEAL])) })
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="protocol-pending"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('shows no notice when every decided appeal has a protocol', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="protocol-pending"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('still lists the versions when the decisions cannot be read', async () => {
    const { mount } = setup({ caseDecidedAppeals: vi.fn(async () => fail('down', 503, 'UNKNOWN')) })
    const { wrapper } = await mount()

    expect(wrapper.findAll('[data-testid="protocol-version"]')).toHaveLength(2)
    expect(wrapper.find('[data-testid="protocol-pending"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows an alert when the list cannot be loaded', async () => {
    const { mount } = setup({ caseProtocols: vi.fn(async () => fail('boom', 500, 'UNKNOWN')) })
    const { wrapper } = await mount()

    expect(wrapper.find('[role="alert"]').text()).toContain('boom')

    wrapper.unmount()
  })
})

describe('ProtocolSection: text', () => {
  it('fetches nothing but the list until a version is opened', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    expect(api.openCaseProtocol).not.toHaveBeenCalled()
    expect(api.downloadCaseProtocol).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('opens the text of the chosen version as plain text, never as markup', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await wrapper.findAll('[data-testid="protocol-open"]')[0]!.trigger('click')
    await flushPromises()

    expect(api.openCaseProtocol).toHaveBeenCalledWith('p-0001', EVIDENCE_2)
    const text = wrapper.get('[data-testid="protocol-text"]')
    expect(text.text()).toContain('Player: <b>hello</b>')
    expect(text.find('b').exists()).toBe(false)
    expect(wrapper.get('[data-testid="protocol-status"]').text()).toBe('Protocol text opened.')

    wrapper.unmount()
  })

  it('closes the text again with the same button', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    await wrapper.findAll('[data-testid="protocol-open"]')[0]!.trigger('click')
    await flushPromises()
    await wrapper.findAll('[data-testid="protocol-open"]')[0]!.trigger('click')

    expect(wrapper.find('[data-testid="protocol-text"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows the server message and no text when a version cannot be opened', async () => {
    const { mount } = setup({ openCaseProtocol: vi.fn(async () => fail('gone', 404, 'NOT_FOUND')) })
    const { wrapper } = await mount()

    await wrapper.findAll('[data-testid="protocol-open"]')[0]!.trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="protocol-error"]').text()).toBe('gone')
    expect(wrapper.find('[data-testid="protocol-text"]').exists()).toBe(false)

    wrapper.unmount()
  })
})

describe('ProtocolSection: download', () => {
  it('downloads the chosen version as text under a name that carries the version', async () => {
    const { created, clicked } = stubDownload()
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await wrapper.findAll('[data-testid="protocol-download-text"]')[1]!.trigger('click')
    await flushPromises()

    expect(api.downloadCaseProtocol).toHaveBeenCalledWith('p-0001', EVIDENCE_1, 'text')
    expect(clicked).toEqual([{ download: 'case-AB12CD-protocol-v1.txt', href: 'blob:test' }])
    expect(await created[0]!.text()).toBe('plain')

    wrapper.unmount()
  })

  it('downloads JSON with a json name', async () => {
    const { clicked } = stubDownload()
    const { api, mount } = setup({ downloadCaseProtocol: vi.fn(async () => ok({ format: 'json' as const, content: '{}', mediaType: 'application/json' })) })
    const { wrapper } = await mount()

    await wrapper.findAll('[data-testid="protocol-download-json"]')[0]!.trigger('click')
    await flushPromises()

    expect(api.downloadCaseProtocol).toHaveBeenCalledWith('p-0001', EVIDENCE_2, 'json')
    expect(clicked[0]!.download).toBe('case-AB12CD-protocol-v2.json')

    wrapper.unmount()
  })

  it('shows the server message and saves nothing when the download fails', async () => {
    const { clicked } = stubDownload()
    const { mount } = setup({ downloadCaseProtocol: vi.fn(async () => fail('no file', 404, 'NOT_FOUND')) })
    const { wrapper } = await mount()

    await wrapper.findAll('[data-testid="protocol-download-text"]')[0]!.trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="protocol-error"]').text()).toBe('no file')
    expect(clicked).toEqual([])

    wrapper.unmount()
  })
})

describe('ProtocolSection: browser storage', () => {
  it('writes nothing to local or session storage', async () => {
    const write = vi.spyOn(Storage.prototype, 'setItem')
    const { mount } = setup()
    const { wrapper } = await mount()

    await wrapper.findAll('[data-testid="protocol-open"]')[0]!.trigger('click')
    await flushPromises()

    expect(write).not.toHaveBeenCalled()

    wrapper.unmount()
  })
})

describe('the case page by role', () => {
  it.each([[['ADMIN', 'MODERATOR', 'VIEWER']], [['VIEWER']]])('shows the protocol section to %j', async (roles) => {
    const api = {
      caseDetail: vi.fn(async () => ok(caseResponse())),
      caseTimeline: vi.fn(async () => ok(timelinePage([timelineItem()]))),
      caseConversation: vi.fn(async () => ok([])),
      caseProtocols: vi.fn(async () => ok([version()])),
      caseDecidedAppeals: vi.fn(async () => ok([APPEAL])),
      openCaseEvents: () => ({ close() {} }),
    }
    const { wrapper } = await mountWithApp(CaseView,{ api: mockApi(api), path: '/cases/p-0001', props: { reference: 'p-0001' }, roles })

    expect(wrapper.find('[data-testid="protocol-section"]').exists()).toBe(true)

    wrapper.unmount()
  })
})
