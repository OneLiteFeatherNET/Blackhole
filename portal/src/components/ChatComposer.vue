<script setup lang="ts">
import { computed, inject, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { MAX_FILES, MAX_TEXT_LENGTH } from '../api/contract'
import { ACCEPT, isImage, precheck, releasePreview, formatSize, type DraftFile } from '../chat/files'
import { SESSION_KEY } from '../session'

/**
 * The message composer: text, attached files with chips, drag and drop, paste. The drafts live in
 * the session state (memory only). Sending is the parent's job; this component only says when.
 */

const props = defineProps<{ sending: boolean; failure: string }>()
const emit = defineEmits<{ send: [] }>()

const { t } = useI18n()
const session = inject(SESSION_KEY)!
const drafts = session.state.drafts

const input = ref<HTMLInputElement | null>(null)
const dragging = ref(false)
const notice = ref('')
let counter = 0

const length = computed(() => drafts.message.trim().length)
const tooLong = computed(() => length.value > MAX_TEXT_LENGTH)
const uploading = computed(() => drafts.files.some((file) => file.state === 'uploading'))
const readyFiles = computed(() => drafts.files.filter((file) => file.state === 'staged'))
const blank = computed(() => length.value === 0 && readyFiles.value.length === 0)
const canSend = computed(() => !blank.value && !tooLong.value && !uploading.value && !props.sending)

function find(key: string): DraftFile | undefined {
  return drafts.files.find((file) => file.key === key)
}

function removeDraft(key: string) {
  const file = find(key)
  if (!file) return
  releasePreview(file)
  drafts.files.splice(drafts.files.indexOf(file), 1)
}

async function upload(key: string, source: File) {
  const result = await session.api.uploadAttachment(source)
  if (!session.alive(result)) return
  const file = find(key)
  if (!file) {
    // Removed while it was uploading: the staged copy is removed too.
    if (result.ok) void session.api.deleteAttachment(result.data.attachmentId)
    return
  }
  if (result.ok) {
    file.state = 'staged'
    file.attachmentId = result.data.attachmentId
  } else {
    file.state = 'failed'
    file.code = result.error.code
  }
}

/** Adds the files that pass the prechecks and starts their upload. The server still decides. */
function addFiles(list: FileList | File[] | null | undefined) {
  if (!list) return
  for (const source of Array.from(list)) {
    const refusal = precheck(source, drafts.files.length)
    if (refusal) {
      notice.value = refusal === 'TOO_MANY' ? t('chat.errors.TOO_MANY', { max: MAX_FILES }) : t(`chat.errors.${refusal}`)
      continue
    }
    notice.value = ''
    counter += 1
    const key = `file-${counter}`
    const preview = isImage(source.type) && typeof URL.createObjectURL === 'function' ? URL.createObjectURL(source) : undefined
    drafts.files.push({ key, name: source.name, size: source.size, type: source.type, state: 'uploading', preview })
    void upload(key, source)
  }
}

function remove(key: string) {
  const file = find(key)
  if (!file) return
  if (file.state === 'staged' && file.attachmentId) void session.api.deleteAttachment(file.attachmentId)
  removeDraft(key)
  notice.value = ''
}

function onPick(event: Event) {
  const target = event.target as HTMLInputElement
  addFiles(target.files)
  target.value = ''
}

function onDragOver(event: DragEvent) {
  if (!event.dataTransfer?.types.includes('Files')) return
  event.preventDefault()
  dragging.value = true
}

function onDragLeave(event: DragEvent) {
  if (event.currentTarget instanceof Node && event.relatedTarget instanceof Node && event.currentTarget.contains(event.relatedTarget)) return
  dragging.value = false
}

function onDrop(event: DragEvent) {
  dragging.value = false
  if (!event.dataTransfer?.files || event.dataTransfer.files.length === 0) return
  event.preventDefault()
  addFiles(event.dataTransfer.files)
}

function onPaste(event: ClipboardEvent) {
  const files = event.clipboardData?.files
  if (!files || files.length === 0) return
  // Pasted files are attached; pasted text stays a normal paste into the textarea.
  event.preventDefault()
  addFiles(files)
}

/** The one way out: a submit (button or Ctrl+Enter) sends only when the gate is open. */
function onSubmit() {
  if (canSend.value) emit('send')
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
    event.preventDefault()
    onSubmit()
  }
}

/** The codes a chip can show; anything else from the server reads as "could not be accepted". */
const CHIP_ERRORS = new Set([
  'UNSUPPORTED_TYPE',
  'TOO_LARGE',
  'FILE_FLAGGED',
  'FILE_ACTIVE_CONTENT',
  'INVALID_FILE',
  'QUOTA',
  'NO_APPEAL',
  'FORBIDDEN',
  'CONFLICT',
  'UNAVAILABLE',
])

function stateLabel(file: DraftFile): string {
  if (file.state === 'uploading') return t('chat.state.uploading')
  if (file.state === 'staged') return t('chat.state.staged')
  return file.code && CHIP_ERRORS.has(file.code) ? t(`chat.errors.${file.code}`) : t('chat.errors.INVALID_FILE')
}
</script>

<template>
  <form
    class="relative flex flex-col gap-3 rounded-md-lg bg-surface-container p-6"
    novalidate
    data-testid="composer"
    @submit.prevent="onSubmit"
    @dragover="onDragOver"
    @dragleave="onDragLeave"
    @drop="onDrop"
  >
    <div
      v-if="dragging"
      class="pointer-events-none absolute inset-2 flex items-center justify-center rounded-md-lg border-2 border-dashed border-primary bg-primary-container/80 text-title-medium text-on-primary-container"
      data-testid="drop-target"
    >
      {{ t('chat.dropHere') }}
    </div>

    <label for="message" class="text-label-large text-on-surface">{{ t('conversation.composer') }}</label>
    <textarea
      id="message"
      v-model="drafts.message"
      rows="4"
      aria-describedby="message-hint message-count"
      :aria-invalid="tooLong"
      class="plain-text rounded-md-xs border border-outline bg-surface p-4 text-body-large text-on-surface focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
      @keydown="onKeydown"
      @paste="onPaste"
    />
    <p id="message-hint" class="text-body-small text-on-surface-variant">{{ t('chat.formatHint') }}</p>
    <p id="message-count" :class="tooLong ? 'text-error' : 'text-on-surface-variant'" class="text-body-small">
      {{ t('conversation.counter', { count: length, max: MAX_TEXT_LENGTH }) }}
    </p>

    <div class="flex flex-wrap items-center gap-3">
      <input ref="input" type="file" multiple :accept="ACCEPT" class="hidden" data-testid="file-input" @change="onPick" />
      <button
        type="button"
        class="md-state inline-flex h-10 items-center justify-center rounded-md-full border border-outline px-6 text-label-large text-primary disabled:opacity-60"
        :disabled="drafts.files.length >= MAX_FILES"
        aria-describedby="file-count"
        @click="input?.click()"
      >
        {{ t('chat.attach') }}
      </button>
      <p id="file-count" class="text-body-small text-on-surface-variant">{{ t('chat.fileCount', { count: drafts.files.length, max: MAX_FILES }) }}</p>
    </div>

    <ul v-if="drafts.files.length > 0" :aria-label="t('chat.attached')" class="flex list-none flex-wrap gap-2 p-0" data-testid="chips">
      <li v-for="file in drafts.files" :key="file.key" class="flex max-w-full items-center gap-2 rounded-md-sm bg-surface-container-high py-1 pl-1 pr-2 text-body-small text-on-surface">
        <img v-if="file.preview" :src="file.preview" :alt="file.name" class="h-10 w-10 rounded-md-xs object-cover" />
        <span class="break-all">{{ file.name }}</span>
        <span class="text-on-surface-variant">{{ formatSize(file.size) }}</span>
        <span :class="file.state === 'failed' ? 'text-error' : 'text-on-surface-variant'">{{ stateLabel(file) }}</span>
        <button
          type="button"
          class="md-state rounded-md-full px-2 text-label-large text-primary"
          :aria-label="t('chat.remove', { name: file.name })"
          @click="remove(file.key)"
        >
          {{ t('chat.removeShort') }}
        </button>
      </li>
    </ul>

    <p v-if="notice" role="alert" class="rounded-md-sm bg-error-container px-4 py-3 text-body-medium text-on-error-container">{{ notice }}</p>
    <div role="alert" aria-live="assertive">
      <p v-if="failure" class="rounded-md-sm bg-error-container px-4 py-3 text-body-medium text-on-error-container">{{ failure }}</p>
    </div>
    <p v-if="uploading" role="status" aria-live="polite" class="text-body-small text-on-surface-variant">{{ t('chat.uploading') }}</p>

    <div>
      <button
        type="submit"
        :disabled="!canSend"
        class="md-state inline-flex h-10 items-center justify-center rounded-md-full bg-primary px-6 text-label-large text-on-primary disabled:opacity-60"
      >
        {{ sending ? t('conversation.sending') : t('conversation.send') }}
      </button>
    </div>
  </form>
</template>
