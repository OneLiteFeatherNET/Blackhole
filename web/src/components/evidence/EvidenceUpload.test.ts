import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { DEFAULT_EVIDENCE_LIMITS } from '../../api/config'
import { API_KEY, EVIDENCE_LIMITS_KEY } from '../../api/inject'
import type { Upload, UploadResult } from '../../api/uploadEvidence'
import { i18n } from '../../i18n'
import { mockApi, mountWithApp } from '../../test/mount'
import EvidenceUpload from './EvidenceUpload.vue'

const PUNISHMENT = 'ab0e5a9e-1111-4222-8333-444455556666'

/** A fake uploader: each call returns an upload the test settles or cancels by hand. */
function fakeUploader() {
  const started: Array<{ file: File; progress?: (fraction: number) => void; settle: (result: UploadResult) => void; cancelled: boolean }> = []
  const start = vi.fn((_punishment: string, file: File, progress?: (fraction: number) => void): Upload => {
    let settle!: (result: UploadResult) => void
    const result = new Promise<UploadResult>((resolve) => (settle = resolve))
    const entry = { file, progress, settle, cancelled: false }
    started.push(entry)
    return {
      result,
      cancel: () => {
        entry.cancelled = true
        settle({ ok: false, error: { kind: 'CANCELLED', status: 0, message: '' } })
      },
    }
  })
  return { start, started }
}

const png = (name = 'shot.png', size = 100) => new File([new Uint8Array(size)], name, { type: 'image/png' })
const mp4 = (name = 'clip.mp4', size = 100) => new File([new Uint8Array(size)], name, { type: 'video/mp4' })

async function mountUpload(uploader = fakeUploader()) {
  const mounted = await mountWithApp(EvidenceUpload, {
    api: mockApi({ startEvidenceUpload: uploader.start }),
    props: { punishment: PUNISHMENT },
  })
  return { ...mounted, uploader }
}

async function drop(wrapper: Awaited<ReturnType<typeof mountUpload>>['wrapper'], files: File[]) {
  await wrapper.get('[data-testid="dropzone"]').trigger('drop', { dataTransfer: { files } })
  await flushPromises()
}

const OK: UploadResult = { ok: true, data: {} as never }

describe('EvidenceUpload', () => {
  it('starts an upload for a dropped file and shows its progress', async () => {
    const { wrapper, uploader } = await mountUpload()

    await drop(wrapper, [png()])
    uploader.started[0]!.progress?.(0.4)
    await flushPromises()

    expect(uploader.start).toHaveBeenCalledWith(PUNISHMENT, expect.any(File), expect.any(Function))
    expect(wrapper.get('[role="progressbar"]').attributes('aria-valuenow')).toBe('40')

    wrapper.unmount()
  })

  it('starts an upload for a picked file', async () => {
    const { wrapper, uploader } = await mountUpload()
    const input = wrapper.get<HTMLInputElement>('[data-testid="picker"]')
    Object.defineProperty(input.element, 'files', { value: [png('picked.png')] })

    await input.trigger('change')
    await flushPromises()

    expect(uploader.started.map((entry) => entry.file.name)).toEqual(['picked.png'])

    wrapper.unmount()
  })

  it('opens the file picker from the keyboard', async () => {
    const { wrapper } = await mountUpload()
    const click = vi.spyOn(wrapper.get<HTMLInputElement>('[data-testid="picker"]').element, 'click')

    await wrapper.get('[data-testid="dropzone"]').trigger('keydown', { key: 'Enter' })
    await wrapper.get('[data-testid="dropzone"]').trigger('keydown', { key: ' ' })

    expect(click).toHaveBeenCalledTimes(2)

    wrapper.unmount()
  })

  it('makes the drop zone a labelled, focusable button', async () => {
    const { wrapper } = await mountUpload()

    const zone = wrapper.get('[data-testid="dropzone"]')
    expect(zone.attributes('role')).toBe('button')
    expect(zone.attributes('tabindex')).toBe('0')
    expect(zone.attributes('aria-label')).toBe('Upload evidence files')

    wrapper.unmount()
  })

  it('uploads several files one after the other', async () => {
    const { wrapper, uploader } = await mountUpload()

    await drop(wrapper, [png('a.png'), png('b.png')])
    expect(uploader.started.map((entry) => entry.file.name), 'only the first runs').toEqual(['a.png'])

    uploader.started[0]!.settle(OK)
    await flushPromises()

    expect(uploader.started.map((entry) => entry.file.name)).toEqual(['a.png', 'b.png'])

    wrapper.unmount()
  })

  it('tells the parent when a file was stored and drops its entry', async () => {
    const { wrapper, uploader } = await mountUpload()

    await drop(wrapper, [png()])
    uploader.started[0]!.settle(OK)
    await flushPromises()

    expect(wrapper.emitted('uploaded')).toHaveLength(1)
    expect(wrapper.find('[data-testid="upload-entry"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('cancels a running upload and removes its entry without error', async () => {
    const { wrapper, uploader } = await mountUpload()

    await drop(wrapper, [png()])
    await wrapper.get('[data-testid="dismiss"]').trigger('click')
    await flushPromises()

    expect(uploader.started[0]!.cancelled).toBe(true)
    expect(wrapper.find('[data-testid="upload-entry"]').exists()).toBe(false)
    expect(wrapper.emitted('uploaded')).toBeUndefined()

    wrapper.unmount()
  })

  it('drops a queued file without ever uploading it', async () => {
    const { wrapper, uploader } = await mountUpload()

    await drop(wrapper, [png('a.png'), png('b.png')])
    await wrapper.findAll('[data-testid="dismiss"]')[1]!.trigger('click')
    uploader.started[0]!.settle(OK)
    await flushPromises()

    expect(uploader.started.map((entry) => entry.file.name)).toEqual(['a.png'])

    wrapper.unmount()
  })

  it.each([
    [415, 'UNSUPPORTED_TYPE', 'This file type is not allowed.'],
    [413, 'TOO_LARGE', 'The file is too large.'],
    [422, 'REJECTED', 'The file was rejected (virus scan or personal data).'],
    [503, 'UNAVAILABLE', 'The virus scanner is not available. Try again later.'],
  ] as const)('shows the answer of HTTP %i on the file and sends no parent event', async (status, kind, text) => {
    const { wrapper, uploader } = await mountUpload()

    await drop(wrapper, [png()])
    uploader.started[0]!.settle({ ok: false, error: { kind, status, message: '' } })
    await flushPromises()

    expect(wrapper.get('[data-testid="upload-error"]').text()).toBe(text)
    expect(wrapper.emitted('uploaded')).toBeUndefined()

    wrapper.unmount()
  })

  it('appends the backend message to the error text', async () => {
    const { wrapper, uploader } = await mountUpload()

    await drop(wrapper, [png()])
    uploader.started[0]!.settle({ ok: false, error: { kind: 'REJECTED', status: 422, message: 'payload contains an IP' } })
    await flushPromises()

    expect(wrapper.get('[data-testid="upload-error"]').text()).toContain('payload contains an IP')

    wrapper.unmount()
  })

  it('does not send a file type the limits do not allow', async () => {
    const { wrapper, uploader } = await mountUpload()

    await drop(wrapper, [new File(['x'], 'doc.pdf', { type: 'application/pdf' })])

    expect(uploader.start).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="upload-error"]').text()).toBe('This file type is not allowed.')

    wrapper.unmount()
  })

  it('does not send an image above the image limit', async () => {
    const { wrapper, uploader } = await mountUpload()

    await drop(wrapper, [png('big.png', DEFAULT_EVIDENCE_LIMITS.maxImageBytes + 1)])

    expect(uploader.start).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="upload-error"]').text()).toBe('The file is larger than 10 MB.')

    wrapper.unmount()
  })

  it('applies the larger video limit to a video', async () => {
    const { wrapper, uploader } = await mountUpload()

    await drop(wrapper, [mp4('clip.mp4', DEFAULT_EVIDENCE_LIMITS.maxImageBytes + 1)])

    expect(uploader.started.map((entry) => entry.file.name)).toEqual(['clip.mp4'])

    wrapper.unmount()
  })

  it('leaves a file with an unknown browser type to the server', async () => {
    const { wrapper, uploader } = await mountUpload()

    await drop(wrapper, [new File(['x'], 'mystery', { type: '' })])

    expect(uploader.start).toHaveBeenCalledTimes(1)

    wrapper.unmount()
  })

  it('announces started and finished uploads in a polite live region', async () => {
    const { wrapper, uploader } = await mountUpload()

    await drop(wrapper, [png()])
    const live = wrapper.get('[data-testid="announcement"]')
    expect(live.attributes('aria-live')).toBe('polite')
    expect(live.text()).toBe('Uploading shot.png')

    uploader.started[0]!.settle(OK)
    await flushPromises()

    expect(live.text()).toBe('shot.png uploaded')

    wrapper.unmount()
  })

  it('cancels a running upload when the panel goes away', async () => {
    const { wrapper, uploader } = await mountUpload()

    await drop(wrapper, [png()])
    wrapper.unmount()

    expect(uploader.started[0]!.cancelled).toBe(true)
  })

  it('takes its limits from the app configuration', async () => {
    const uploader = fakeUploader()
    const limited = mount(EvidenceUpload, {
      props: { punishment: PUNISHMENT },
      global: {
        plugins: [i18n],
        provide: {
          [API_KEY as symbol]: mockApi({ startEvidenceUpload: uploader.start }),
          [EVIDENCE_LIMITS_KEY as symbol]: { maxImageBytes: 50, maxVideoBytes: 60, allowedTypes: ['image/png'] },
        },
      },
    })

    await limited.get('[data-testid="dropzone"]').trigger('drop', { dataTransfer: { files: [png('big.png', 51)] } })

    expect(uploader.start).not.toHaveBeenCalled()
    limited.unmount()
  })
})
