<script setup lang="ts">
import { computed, inject, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { MAX_FILES, MAX_TEXT_LENGTH } from '../api/contract'
import { useAttachments } from '../chat/attachments'
import { ACCEPT } from '../chat/files'
import { SESSION_KEY } from '../session'
import FileChips from './FileChips.vue'

/**
 * The message composer: text, attached files with chips, drag and drop, paste. The drafts live in
 * the session state (memory only). Sending is the parent's job; this component only says when.
 */

const props = defineProps<{ sending: boolean; failure: string }>()
const emit = defineEmits<{ send: [] }>()

const { t } = useI18n()
const session = inject(SESSION_KEY)!
const drafts = session.state.drafts
const { notice, addFiles, removeFile } = useAttachments(session, { upload: true })

const input = ref<HTMLInputElement | null>(null)
const dragging = ref(false)

const length = computed(() => drafts.message.trim().length)
const tooLong = computed(() => length.value > MAX_TEXT_LENGTH)
const uploading = computed(() => drafts.files.some((file) => file.state === 'uploading'))
const readyFiles = computed(() => drafts.files.filter((file) => file.state === 'staged'))
const blank = computed(() => length.value === 0 && readyFiles.value.length === 0)
const canSend = computed(() => !blank.value && !tooLong.value && !uploading.value && !props.sending)

/**
 * Enter sends only where a keyboard is the pointer. A phone has no Shift+Enter habit and a sent message
 * cannot be edited, so there Enter stays a new line. No matchMedia (an old browser) counts as no fine pointer.
 */
function hasFinePointer(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia('(pointer: coarse)').matches === false
}

const hint = hasFinePointer() ? `${t('chat.formatHint')} ${t('chat.enterHint')}` : t('chat.formatHint')

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

/** The one way out: a submit (button or Enter) sends only when the gate is open. */
function onSubmit() {
  if (canSend.value) emit('send')
}

/** Ctrl/Cmd+Enter always sends. A plain Enter sends on a fine pointer, not with Shift and not while an IME composes. */
function onKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter') return
  const plain = !event.shiftKey && !event.isComposing && hasFinePointer()
  if (!(event.ctrlKey || event.metaKey || plain)) return
  event.preventDefault()
  onSubmit()
}
</script>

<template>
  <form
    class="relative flex flex-col gap-2 rounded-md-lg bg-surface-container p-3"
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
    <div class="flex flex-wrap items-end gap-2">
      <textarea
        id="message"
        v-model="drafts.message"
        rows="2"
        aria-describedby="message-hint message-count"
        :aria-invalid="tooLong"
        class="plain-text min-w-0 flex-1 basis-56 rounded-md-xs border border-outline bg-surface p-3 text-body-large text-on-surface focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
        @keydown="onKeydown"
        @paste="onPaste"
      />
      <div class="ml-auto flex items-center gap-2">
        <input ref="input" type="file" multiple :accept="ACCEPT" class="hidden" data-testid="file-input" @change="onPick" />
        <button
          type="button"
          class="md-state inline-flex h-10 items-center justify-center rounded-md-full border border-outline px-4 text-label-large text-primary disabled:opacity-60"
          :disabled="drafts.files.length >= MAX_FILES"
          aria-describedby="file-count"
          @click="input?.click()"
        >
          {{ t('chat.attach') }}
        </button>
        <button
          type="submit"
          :disabled="!canSend"
          class="md-state inline-flex h-10 items-center justify-center rounded-md-full bg-primary px-4 text-label-large text-on-primary disabled:opacity-60"
        >
          {{ sending ? t('conversation.sending') : t('conversation.send') }}
        </button>
      </div>
    </div>
    <p id="message-hint" class="text-body-small text-on-surface-variant">{{ hint }}</p>
    <div class="flex flex-wrap gap-x-4 gap-y-1">
      <p id="message-count" :class="tooLong ? 'text-error' : 'text-on-surface-variant'" class="text-body-small">
        {{ t('conversation.counter', { count: length, max: MAX_TEXT_LENGTH }) }}
      </p>
      <p id="file-count" class="text-body-small text-on-surface-variant">{{ t('chat.fileCount', { count: drafts.files.length, max: MAX_FILES }) }}</p>
    </div>

    <FileChips :files="drafts.files" @remove="removeFile" />

    <p v-if="notice" role="alert" class="rounded-md-sm bg-error-container px-4 py-3 text-body-medium text-on-error-container">{{ notice }}</p>
    <div role="alert" aria-live="assertive">
      <p v-if="failure" class="rounded-md-sm bg-error-container px-4 py-3 text-body-medium text-on-error-container">{{ failure }}</p>
    </div>
    <p v-if="uploading" role="status" aria-live="polite" class="text-body-small text-on-surface-variant">{{ t('chat.uploading') }}</p>
  </form>
</template>
