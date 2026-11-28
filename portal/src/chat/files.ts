import { MAX_FILE_BYTES, MAX_FILES, type ErrorCode } from '../api/contract'

/**
 * Client prechecks for a chat file. They are conveniences: the server decides, and its answer is what
 * the composer shows when it refuses a file that passed here.
 */

const TYPES = new Set(['image/png', 'image/jpeg', 'image/webp', 'application/pdf', 'text/plain'])
const EXTENSIONS = new Set(['png', 'jpg', 'jpeg', 'webp', 'pdf', 'txt'])

/** The `accept` attribute of the file picker. */
export const ACCEPT = '.png,.jpg,.jpeg,.webp,.pdf,.txt,image/png,image/jpeg,image/webp,application/pdf,text/plain'

export function extensionOf(name: string): string {
  const dot = name.lastIndexOf('.')
  return dot === -1 ? '' : name.slice(dot + 1).toLowerCase()
}

export function isImage(type: string): boolean {
  return type === 'image/png' || type === 'image/jpeg' || type === 'image/webp'
}

/** What the precheck refuses, or null when the file may be uploaded. */
export function precheck(file: Pick<File, 'name' | 'size' | 'type'>, staged: number): 'TOO_MANY' | ErrorCode | null {
  if (staged >= MAX_FILES) return 'TOO_MANY'
  if (file.size > MAX_FILE_BYTES) return 'TOO_LARGE'
  const known = TYPES.has(file.type) || (file.type === '' && EXTENSIONS.has(extensionOf(file.name)))
  return known ? null : 'UNSUPPORTED_TYPE'
}

/** `12 KB`, `3.4 MB`: a size in the viewer's terms, never a byte count for a file this size. */
export function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

/**
 * A file in the composer: staged on the server once `attachmentId` is set. `picked` is a file of the
 * appeal form that waits in memory until the appeal is filed. Lives in memory only.
 */
export interface DraftFile {
  key: string
  name: string
  size: number
  type: string
  state: 'uploading' | 'staged' | 'failed' | 'picked'
  attachmentId?: string
  /** A local object URL for the preview of an image; released when the file leaves the draft. */
  preview?: string
  code?: ErrorCode
  /** The picked file itself, kept until it is uploaded. */
  source?: File
}

/** Frees the preview of a draft file. jsdom has no object URLs, so the call is guarded. */
export function releasePreview(file: DraftFile): void {
  if (file.preview !== undefined && typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(file.preview)
}
