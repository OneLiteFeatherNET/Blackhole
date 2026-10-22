import { API_VERSION, API_VERSION_HEADER } from './http'
import { EVIDENCE_SOURCE_SYSTEM } from './blackhole'
import type { Evidence } from './types'

/** Why an upload failed; the first four are the backend's answers (413, 415, 422, 503). */
export type UploadErrorKind = 'TOO_LARGE' | 'UNSUPPORTED_TYPE' | 'REJECTED' | 'UNAVAILABLE' | 'CANCELLED' | 'NETWORK' | 'FAILED'

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
  /** The operator UUID, sent as `source`. */
  source: string
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
  const xhr = (request.createRequest ?? (() => new XMLHttpRequest()))()

  const result = new Promise<UploadResult>((resolve) => {
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
    form.append('source', request.source)
    form.append('file', request.file, request.file.name)

    xhr.open('POST', `${request.baseUrl}/evidence/file`)
    xhr.setRequestHeader(API_VERSION_HEADER, API_VERSION)
    xhr.setRequestHeader('Accept', 'application/json')
    xhr.send(form)
  })

  return { result, cancel: () => xhr.abort() }
}
