import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { fakeHttpAuth } from '../test/auth'
import type { paths } from './schema'
import { uploadEvidence, type UploadRequest } from './uploadEvidence'

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

/** Starts an upload and lets the token lookup settle, so the first request has been sent. */
async function start(overrides: Partial<UploadRequest> = {}, auths = fakeHttpAuth()) {
  const sent: FakeXhr[] = []
  const file = new File(['png-bytes'], 'shot.png', { type: 'image/png' })
  const upload = uploadEvidence({
    baseUrl: '/api',
    auth: auths.auth,
    punishment: PUNISHMENT,
    file,
    createRequest: () => {
      const created = new FakeXhr()
      sent.push(created)
      return created as unknown as XMLHttpRequest
    },
    ...overrides,
  })
  await flushPromises()
  return { xhr: sent[0]!, sent, upload, file, events: auths.events }
}

describe('uploadEvidence', () => {
  it('posts a multipart form to the file endpoint with the API version', async () => {
    const { xhr } = await start()

    expect(xhr.method).toBe('POST')
    expect(xhr.url).toBe('/api/evidence/file')
    expect(xhr.headers['X-API-VERSION'], 'version header').toBe('1')
  })

  it('sends the text fields before the file part, using fields the API declares', async () => {
    const { xhr, file } = await start()

    type Declared = keyof NonNullable<paths['/evidence/file']['post']['requestBody']>['content']['multipart/form-data']
    const declared: Declared[] = ['punishment', 'sourceSystem', 'file']
    const names = Array.from(xhr.body!.keys())
    expect(names).toEqual(declared)
    expect(xhr.body!.get('punishment')).toBe(PUNISHMENT)
    expect(xhr.body!.has('source'), 'client-supplied actor').toBe(false)
    expect(xhr.body!.get('sourceSystem')).toBe('admin-ui')
    expect((xhr.body!.get('file') as File).name).toBe(file.name)
  })

  it('reports progress as a fraction of the bytes sent', async () => {
    const onProgress = vi.fn()
    const { xhr } = await start({ onProgress })

    xhr.upload.onprogress?.({ lengthComputable: true, loaded: 25, total: 100 })
    xhr.upload.onprogress?.({ lengthComputable: true, loaded: 100, total: 100 })

    expect(onProgress.mock.calls).toEqual([[0.25], [1]])
  })

  it('ignores progress events without a known total', async () => {
    const onProgress = vi.fn()
    const { xhr } = await start({ onProgress })

    xhr.upload.onprogress?.({ lengthComputable: false, loaded: 25, total: 0 })

    expect(onProgress).not.toHaveBeenCalled()
  })

  it('resolves with the stored item on success', async () => {
    const { xhr, upload } = await start()

    xhr.answer(200, { identifier: 'item-1', kind: 'FILE' })

    expect(await upload.result).toEqual({ ok: true, data: { identifier: 'item-1', kind: 'FILE' } })
  })

  it.each([
    [403, 'FORBIDDEN'],
    [413, 'TOO_LARGE'],
    [415, 'UNSUPPORTED_TYPE'],
    [422, 'REJECTED'],
    [503, 'UNAVAILABLE'],
    [500, 'FAILED'],
  ] as const)('maps HTTP %i to %s and keeps the backend message', async (status, kind) => {
    const { xhr, upload } = await start()

    xhr.answer(status, { errorMessage: 'the reason', kind: 'VALIDATION' })

    expect(await upload.result).toEqual({ ok: false, error: { kind, status, message: 'the reason' } })
  })

  it('maps an answer that is not JSON to an error without a message', async () => {
    const { xhr, upload } = await start()

    xhr.answer(413, '<html>Request Entity Too Large</html>')

    expect(await upload.result).toEqual({ ok: false, error: { kind: 'TOO_LARGE', status: 413, message: '' } })
  })

  it('fails with FAILED when a success answer is not JSON', async () => {
    const { xhr, upload } = await start()

    xhr.answer(200, 'not json')

    expect(await upload.result).toEqual({ ok: false, error: { kind: 'FAILED', status: 200, message: '' } })
  })

  it('aborts the request on cancel and resolves with CANCELLED', async () => {
    const { xhr, upload } = await start()

    upload.cancel()

    expect(xhr.aborted).toBe(true)
    expect(await upload.result).toEqual({ ok: false, error: { kind: 'CANCELLED', status: 0, message: '' } })
  })

  it('resolves with NETWORK when the request cannot be sent', async () => {
    const { xhr, upload } = await start()

    xhr.onerror?.()

    expect(await upload.result).toEqual({ ok: false, error: { kind: 'NETWORK', status: 0, message: '' } })
  })

  it('sends the bearer token', async () => {
    const { xhr } = await start()

    expect(xhr.headers['Authorization']).toBe('Bearer access-token')
  })

  it('renews the token once and repeats the upload after a 401', async () => {
    const { xhr, sent, upload, events } = await start()

    xhr.answer(401, { errorMessage: 'expired' })
    await flushPromises()
    sent[1]!.answer(200, { identifier: 'item-1', kind: 'FILE' })

    expect(sent, 'requests sent').toHaveLength(2)
    expect(sent[1]!.headers['Authorization'], 'retried with the renewed token').toBe('Bearer renewed-token')
    expect(events.renewals, 'renewals').toBe(1)
    expect(await upload.result).toEqual({ ok: true, data: { identifier: 'item-1', kind: 'FILE' } })
  })

  it('signs in again when the repeated upload is refused with 401 too', async () => {
    const { xhr, sent, upload, events } = await start()

    xhr.answer(401, {})
    await flushPromises()
    sent[1]!.answer(401, {})
    await upload.result

    expect(sent, 'requests sent').toHaveLength(2)
    expect(events.signIns, 'sign-ins').toBe(1)
  })

  it('signs in again without a second request when the renewal fails', async () => {
    const { xhr, sent, events } = await start({}, fakeHttpAuth({ renewed: null }))

    xhr.answer(401, {})
    await flushPromises()

    expect(sent, 'requests sent').toHaveLength(1)
    expect(events.signIns, 'sign-ins').toBe(1)
  })

  it('reports a 403 and does not repeat the upload', async () => {
    const { xhr, sent, upload, events } = await start()

    xhr.answer(403, {})
    await upload.result

    expect(sent, 'requests sent').toHaveLength(1)
    expect(events.forbidden, 'forbidden notices').toBe(1)
    expect(events.renewals, 'renewals').toBe(0)
  })

  it('renews first when there is no token yet', async () => {
    const { xhr, events } = await start({}, fakeHttpAuth({ token: null }))

    expect(events.renewals, 'renewals').toBe(1)
    expect(xhr.headers['Authorization']).toBe('Bearer renewed-token')
  })
})
