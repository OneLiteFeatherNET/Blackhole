import { API_VERSION, API_VERSION_HEADER, type HttpAuth } from './http'
import type { Evidence } from './types'

/** Evidence the UI attaches is attributed to this system name (the backend requires one). */
export const EVIDENCE_SOURCE_SYSTEM = 'admin-ui'

/** Why an upload failed; the first five are the backend's answers (403, 413, 415, 422, 503). */
export type UploadErrorKind = 'FORBIDDEN' | 'TOO_LARGE' | 'UNSUPPORTED_TYPE' | 'REJECTED' | 'UNAVAILABLE' | 'CANCELLED' | 'NETWORK' | 'FAILED'

export interface UploadError {
  kind: UploadErrorKind
  /** HTTP status, 0 when the request never got an answer. */
  status: number
  /** The backend's message when it sent one; the UI shows its own text per {@link UploadErrorKind}. */
  message: string
}

export type UploadResult = { ok: true; data: Evidence } | { ok: false; error: UploadError }

export interface UploadRequest {
  baseUrl: string
  /** Supplies the bearer token; an upload is renewed and repeated once on 401, like every other call. */
  auth: HttpAuth
  /** The punishment's identifier or short id. */
  punishment: string
  file: File
  /** Called with 0..1 while the file is being sent. */
  onProgress?: (fraction: number) => void
  /** Only tests pass this; the default is a real `XMLHttpRequest`. */
  createRequest?: () => XMLHttpRequest
}

export interface Upload {
  result: Promise<UploadResult>
  /** Aborts the request; {@link result} then resolves with a CANCELLED error. */
  cancel: () => void
}

const KIND_BY_STATUS: Record<number, UploadErrorKind> = {
  403: 'FORBIDDEN',
  413: 'TOO_LARGE',
  415: 'UNSUPPORTED_TYPE',
  422: 'REJECTED',
  503: 'UNAVAILABLE',
}

function messageOf(text: string): string {
  try {
    const body = JSON.parse(text) as { errorMessage?: unknown } | null
    return typeof body?.errorMessage === 'string' ? body.errorMessage : ''
  } catch {
    return ''
  }
}

/**
 * Uploads one evidence file as multipart form data. `fetch` cannot report upload progress, so this
 * is the one call that uses `XMLHttpRequest`. Never throws: the result carries a typed error.
 */
export function uploadEvidence(request: UploadRequest): Upload {
  const createRequest = request.createRequest ?? (() => new XMLHttpRequest())
  let current: XMLHttpRequest | null = null
  let cancelled = false

  const attempt = (token: string): Promise<UploadResult> => {
    const xhr = createRequest()
    current = xhr
    return new Promise<UploadResult>((resolve) => {
      const fail = (kind: UploadErrorKind, status: number, message = ''): void =>
        resolve({ ok: false, error: { kind, status, message } })

      xhr.upload.onprogress = (event) => {
        if (event.lengthComputable && event.total > 0) request.onProgress?.(event.loaded / event.total)
      }
      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          try {
            resolve({ ok: true, data: JSON.parse(xhr.responseText) as Evidence })
          } catch {
            fail('FAILED', xhr.status)
          }
          return
        }
        fail(KIND_BY_STATUS[xhr.status] ?? 'FAILED', xhr.status, messageOf(xhr.responseText))
      }
      xhr.onerror = () => fail('NETWORK', 0)
      xhr.ontimeout = () => fail('NETWORK', 0)
      xhr.onabort = () => fail('CANCELLED', 0)

      // The backend reads the text fields before the file part, so the file goes last.
      const form = new FormData()
      form.append('punishment', request.punishment)
      form.append('sourceSystem', EVIDENCE_SOURCE_SYSTEM)
      form.append('file', request.file, request.file.name)

      xhr.open('POST', `${request.baseUrl}/evidence/file`)
      xhr.setRequestHeader(API_VERSION_HEADER, API_VERSION)
      xhr.setRequestHeader('Accept', 'application/json')
      xhr.setRequestHeader('Authorization', `Bearer ${token}`)
      xhr.send(form)
    })
  }

  const cancelledResult: UploadResult = { ok: false, error: { kind: 'CANCELLED', status: 0, message: '' } }
  const isUnauthorized = (result: UploadResult) => !result.ok && result.error.status === 401

  const result = (async (): Promise<UploadResult> => {
    const token = await request.auth.getAccessToken()
    if (cancelled) return cancelledResult
    let outcome = token ? await attempt(token) : null
    if (outcome === null || isUnauthorized(outcome)) {
      const renewed = await request.auth.renew()
      if (cancelled) return cancelledResult
      if (!renewed) {
        request.auth.signIn()
        return outcome ?? { ok: false, error: { kind: 'FAILED', status: 401, message: '' } }
      }
      outcome = await attempt(renewed)
      if (isUnauthorized(outcome)) request.auth.signIn()
    }
    if (!outcome.ok && outcome.error.kind === 'FORBIDDEN') request.auth.forbidden()
    return outcome
  })()

  return {
    result,
    cancel: () => {
      cancelled = true
      current?.abort()
    },
  }
}
