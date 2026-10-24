import { flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import { chooseOption } from '../../test/select'
import CaseView from '../../views/CaseView.vue'
import { caseResponse, timelineItem, timelinePage } from '../../test/cases'
import TranscriptExport from './TranscriptExport.vue'

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
  const api = { exportCaseTranscript: vi.fn(async () => ok({ format: 'json' as const, content: '{"entries":[]}', mediaType: 'application/json' })), ...extra }
  return { api, mount: () => mountWithApp(TranscriptExport, { api: mockApi(api), props: { reference: 'p-0001', shortId: '#AB12CD' } }) }
}

describe('TranscriptExport', () => {
  it('fetches nothing until the button is pressed', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    expect(api.exportCaseTranscript).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('downloads JSON without notes by default', async () => {
    const { created, clicked } = stubDownload()
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="transcript-download"]').trigger('click')
    await flushPromises()

    expect(api.exportCaseTranscript).toHaveBeenCalledWith('p-0001', 'json', false)
    expect(clicked).toEqual([{ download: 'case-AB12CD-transcript.json', href: 'blob:test' }])
    expect(await created[0]!.text()).toBe('{"entries":[]}')
    expect(wrapper.emitted('exported')?.[0]).toEqual(['Transcript exported.'])

    wrapper.unmount()
  })

  it('downloads text with the notes when both are chosen', async () => {
    const { clicked } = stubDownload()
    const { api, mount } = setup({ exportCaseTranscript: vi.fn(async () => ok({ format: 'text' as const, content: 'plain', mediaType: 'text/plain' })) })
    const { wrapper } = await mount()

    await chooseOption(wrapper.get('[data-testid="transcript-format"] [role="combobox"]'), 'text')
    await wrapper.get('[data-testid="transcript-notes"] input').setValue(true)
    await wrapper.get('[data-testid="transcript-download"]').trigger('click')
    await flushPromises()

    expect(api.exportCaseTranscript).toHaveBeenCalledWith('p-0001', 'text', true)
    expect(clicked[0]!.download).toBe('case-AB12CD-transcript.txt')

    wrapper.unmount()
  })

  it('shows the server message and downloads nothing when the export fails', async () => {
    const { clicked } = stubDownload()
    const { mount } = setup({ exportCaseTranscript: vi.fn(async () => fail('forbidden', 403, 'UNKNOWN')) })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="transcript-download"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="transcript-error"]').text()).toBe('forbidden')
    expect(clicked).toEqual([])

    wrapper.unmount()
  })
})

describe('the case page by role', () => {
  function page(roles: string[]) {
    const api = {
      caseDetail: vi.fn(async () => ok(caseResponse())),
      caseTimeline: vi.fn(async () => ok(timelinePage([timelineItem()]))),
      caseConversation: vi.fn(async () => ok([])),
      caseProtocols: vi.fn(async () => ok([])),
      caseDecidedAppeals: vi.fn(async () => ok([])),
      openCaseEvents: () => ({ close() {} }),
    }
    return mountWithApp(CaseView, { api: mockApi(api), path: '/cases/p-0001', props: { reference: 'p-0001' }, roles })
  }

  it('offers the export to an admin only', async () => {
    const admin = await page(['ADMIN', 'MODERATOR', 'VIEWER'])
    expect(admin.wrapper.find('[data-testid="transcript-export"]').exists()).toBe(true)
    admin.wrapper.unmount()

    const moderator = await page(['MODERATOR', 'VIEWER'])
    expect(moderator.wrapper.find('[data-testid="transcript-export"]').exists()).toBe(false)
    expect(moderator.wrapper.find('[data-testid="transcript-download"]').exists()).toBe(false)
    moderator.wrapper.unmount()
  })

  it('gives a viewer a read-only conversation and Ban ID section', async () => {
    const { wrapper } = await page(['VIEWER'])

    expect(wrapper.find('[data-testid="conversation"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="message-composer"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="ban-id-toggle"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="transcript-export"]').exists()).toBe(false)

    wrapper.unmount()
  })
})
