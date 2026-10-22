import { describe, expect, it, vi } from 'vitest'
import type { paths } from './schema'
import { uploadEvidence, type UploadRequest } from './uploadEvidence'

const SOURCE = '11111111-2222-3333-4444-555555555555'
const PUNISHMENT = 'ab0e5a9e-1111-4222-8333-444455556666'

/** The smallest XMLHttpRequest the uploader touches; the test fires its events by hand. */
class FakeXhr {
  upload: { onprogress: ((event: { lengthComputable: boolean; loaded: number; total: number }) => void) | null } = { onprogress: null }
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  ontimeout: (() => void) | null = null
  onabort: (() => void) | null = null
  status = 0
  responseText = ''
  method = ''
  url = ''
  headers: Record<string, string> = {}
  body: FormData | null = null
  aborted = false

  open(method: string, url: string) {
    this.method = method
    this.url = url
  }
  setRequestHeader(name: string, value: string) {
    this.headers[name] = value
  }
  send(body: FormData) {
    this.body = body
  }
  abort() {
    this.aborted = true
    this.onabort?.()
  }
  answer(status: number, body: unknown) {
    this.status = status
    this.responseText = typeof body === 'string' ? body : JSON.stringify(body)
    this.onload?.()
  }
}

function start(overrides: Partial<UploadRequest> = {}) {
  const xhr = new FakeXhr()
  const file = new File(['png-bytes'], 'shot.png', { type: 'image/png' })
  const upload = uploadEvidence({
    baseUrl: '/api',
    source: SOURCE,
    punishment: PUNISHMENT,
    file,
    createRequest: () => xhr as unknown as XMLHttpRequest,
    ...overrides,
  })
  return { xhr, upload, file }
}

describe('uploadEvidence', () => {
  it('posts a multipart form to the file endpoint with the API version', () => {
    const { xhr } = start()

    expect(xhr.method).toBe('POST')
    expect(xhr.url).toBe('/api/evidence/file')
    expect(xhr.headers['X-API-VERSION'], 'version header').toBe('1')
  })

  it('sends the text fields before the file part, using fields the API declares', () => {
    const { xhr, file } = start()

    type Declared = keyof NonNullable<paths['/evidence/file']['post']['requestBody']>['content']['multipart/form-data']
    const declared: Declared[] = ['punishment', 'sourceSystem', 'source', 'file']
    const names = Array.from(xhr.body!.keys())
    expect(names).toEqual(declared)
    expect(xhr.body!.get('punishment')).toBe(PUNISHMENT)
    expect(xhr.body!.get('source')).toBe(SOURCE)
    expect(xhr.body!.get('sourceSystem')).toBe('admin-ui')
    expect((xhr.body!.get('file') as File).name).toBe(file.name)
  })

  it('reports progress as a fraction of the bytes sent', () => {
    const onProgress = vi.fn()
    const { xhr } = start({ onProgress })

    xhr.upload.onprogress?.({ lengthComputable: true, loaded: 25, total: 100 })
    xhr.upload.onprogress?.({ lengthComputable: true, loaded: 100, total: 100 })

    expect(onProgress.mock.calls).toEqual([[0.25], [1]])
  })

  it('ignores progress events without a known total', () => {
    const onProgress = vi.fn()
    const { xhr } = start({ onProgress })

    xhr.upload.onprogress?.({ lengthComputable: false, loaded: 25, total: 0 })

    expect(onProgress).not.toHaveBeenCalled()
  })

  it('resolves with the stored item on success', async () => {
    const { xhr, upload } = start()

    xhr.answer(200, { identifier: 'item-1', kind: 'FILE' })

    expect(await upload.result).toEqual({ ok: true, data: { identifier: 'item-1', kind: 'FILE' } })
  })

  it.each([
    [413, 'TOO_LARGE'],
    [415, 'UNSUPPORTED_TYPE'],
    [422, 'REJECTED'],
    [503, 'UNAVAILABLE'],
    [500, 'FAILED'],
  ] as const)('maps HTTP %i to %s and keeps the backend message', async (status, kind) => {
    const { xhr, upload } = start()

    xhr.answer(status, { errorMessage: 'the reason', kind: 'VALIDATION' })

    expect(await upload.result).toEqual({ ok: false, error: { kind, status, message: 'the reason' } })
  })

  it('maps an answer that is not JSON to an error without a message', async () => {
    const { xhr, upload } = start()

    xhr.answer(413, '<html>Request Entity Too Large</html>')

    expect(await upload.result).toEqual({ ok: false, error: { kind: 'TOO_LARGE', status: 413, message: '' } })
  })

  it('fails with FAILED when a success answer is not JSON', async () => {
    const { xhr, upload } = start()

    xhr.answer(200, 'not json')

    expect(await upload.result).toEqual({ ok: false, error: { kind: 'FAILED', status: 200, message: '' } })
  })

  it('aborts the request on cancel and resolves with CANCELLED', async () => {
    const { xhr, upload } = start()

    upload.cancel()

    expect(xhr.aborted).toBe(true)
    expect(await upload.result).toEqual({ ok: false, error: { kind: 'CANCELLED', status: 0, message: '' } })
  })

  it('resolves with NETWORK when the request cannot be sent', async () => {
    const { xhr, upload } = start()

    xhr.onerror?.()

    expect(await upload.result).toEqual({ ok: false, error: { kind: 'NETWORK', status: 0, message: '' } })
  })
})
