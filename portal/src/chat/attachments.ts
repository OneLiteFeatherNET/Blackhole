import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { MAX_FILES, type ErrorCode } from '../api/contract'
import type { PortalSession } from '../session'
import { isImage, precheck, releasePreview } from './files'

/**
 * Picking files for a message or an appeal: the same checks, the same chips, the same removal. The
 * composer uploads a file at once (`upload: true`); the appeal form keeps it as `picked` until the
 * appeal is filed, because an upload needs an open appeal.
 */
export function useAttachments(session: PortalSession, options: { upload: boolean }) {
  const { t } = useI18n()
  const drafts = session.state.drafts
  /** Why the last picked file was refused, in words; empty when the last one was accepted. */
  const notice = ref('')

  function refusalText(refusal: 'TOO_MANY' | ErrorCode): string {
    return refusal === 'TOO_MANY' ? t('chat.errors.TOO_MANY', { max: MAX_FILES }) : t(`chat.errors.${refusal}`)
  }

  /** Adds the files that pass the prechecks. The server still decides, so an accepted file may still be refused on upload. */
  function addFiles(list: FileList | File[] | null | undefined) {
    if (!list) return
    for (const source of Array.from(list)) {
      const refusal = precheck(source, drafts.files.length)
      if (refusal) {
        notice.value = refusalText(refusal)
        continue
      }
      notice.value = ''
      const key = crypto.randomUUID()
      const preview = isImage(source.type) && typeof URL.createObjectURL === 'function' ? URL.createObjectURL(source) : undefined
      drafts.files.push({ key, name: source.name, size: source.size, type: source.type, state: options.upload ? 'uploading' : 'picked', preview, source })
      if (options.upload) void stageDraft(session, key, source)
    }
  }

  /** Removes a file from the drafts; a staged copy on the server is deleted too. */
  function removeFile(key: string) {
    const file = drafts.files.find((candidate) => candidate.key === key)
    if (!file) return
    if (file.state === 'staged' && file.attachmentId) void session.api.deleteAttachment(file.attachmentId)
    releasePreview(file)
    drafts.files.splice(drafts.files.indexOf(file), 1)
    notice.value = ''
  }

  return { notice, addFiles, removeFile }
}

/**
 * Uploads one picked file and records the answer on its draft. A file removed while it was uploading
 * gets its staged copy deleted again. Returns false when the session ended.
 */
export async function stageDraft(session: PortalSession, key: string, source: File): Promise<boolean> {
  const result = await session.api.uploadAttachment(source)
  if (!session.alive(result)) return false
  const file = session.state.drafts.files.find((candidate) => candidate.key === key)
  if (!file) {
    if (result.ok) void session.api.deleteAttachment(result.data.attachmentId)
    return true
  }
  if (result.ok) {
    file.state = 'staged'
    file.attachmentId = result.data.attachmentId
  } else {
    file.state = 'failed'
    file.code = result.error.code
  }
  return true
}
