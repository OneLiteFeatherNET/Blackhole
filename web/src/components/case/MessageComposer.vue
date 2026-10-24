<script setup lang="ts">
// Writes or edits one message to the player. The server decides what is acceptable (no UUID, IP
// address or Ban ID, the file checks); the checks here only save a round trip. Files are staged as
// they are chosen and bound to the message when it is sent. A failed send keeps the text, the files
// and the idempotency key, so pressing send again can never post the message twice.
import { computed, ref, useId, useTemplateRef } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ApiResult, ChatAttachment } from '../../api/types'
import MdButton from '../md/MdButton.vue'
import { MAX_CASE_MESSAGE_LENGTH } from './conversation'

/** The backend's `blackhole.chat.attachments.*` defaults. */
const MAX_FILES = 5
const MAX_FILE_BYTES = 10 * 1024 * 1024
const ALLOWED_FILE_TYPES = ['image/png', 'image/jpeg', 'image/webp', 'application/pdf', 'text/plain'] as const

type ChipState = 'uploading' | 'ready' | 'failed'

interface Chip {
  id: number
  name: string
  size: number
  state: ChipState
  attachmentId: string | null
  /** Why the file was refused; shown on the chip. */
  message: string
}

const props = defineProps<{
  label: string
  submitLabel: string
  /** Receives the text, the idempotency key of this attempt and the ids of the ready files. */
  save: (text: string, key: string, attachmentIds: string[]) => Promise<ApiResult<unknown>>
  initial?: string
  /** Offers a cancel button (editing an existing message). */
  cancellable?: boolean
  /** Stages one file with the server; without it the composer takes no files (an edit). */
  upload?: (file: File) => Promise<ApiResult<ChatAttachment>>
}>()
const emit = defineEmits<{ saved: []; cancel: [] }>()

const { t } = useI18n()
const id = useId()
const text = ref(props.initial ?? '')
const error = ref('')
const saving = ref(false)
const chips = ref<Chip[]>([])
const dragging = ref(false)
const announcement = ref('')
const fileInput = useTemplateRef<HTMLInputElement>('fileInput')
let key = crypto.randomUUID()
let nextChip = 1

const length = computed(() => text.value.length)
const readyIds = computed(() => chips.value.filter((chip) => chip.state === 'ready' && chip.attachmentId !== null).map((chip) => chip.attachmentId as string))
const filesBusy = computed(() => chips.value.some((chip) => chip.state !== 'ready'))
const valid = computed(() => (text.value.trim().length > 0 || readyIds.value.length > 0) && length.value <= MAX_CASE_MESSAGE_LENGTH)
const canSend = computed(() => !saving.value && !filesBusy.value && valid.value)

function sizeLabel(bytes: number): string {
  return bytes >= 1024 * 1024 ? `${(bytes / (1024 * 1024)).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`
}

/** The server's refusal, as the user should read it; the codes the backend uses have their own sentence. */
function refusalOf(status: number, message: string): string {
  if (status === 415) return t('case.files.type')
  if (status === 413) return t('case.files.tooLarge', { max: MAX_FILE_BYTES / (1024 * 1024) })
  if (status === 422) return t('case.files.rejected')
  if (status === 503) return t('case.files.unavailable')
  return message || t('case.files.failed')
}

function chipWith(id: number, change: Partial<Chip>): void {
  chips.value = chips.value.map((chip) => (chip.id === id ? { ...chip, ...change } : chip))
}

async function stage(file: File): Promise<void> {
  if (!props.upload) return
  if (chips.value.length >= MAX_FILES) {
    error.value = t('case.files.max', { max: MAX_FILES })
    return
  }
  const chip: Chip = { id: nextChip++, name: file.name, size: file.size, state: 'uploading', attachmentId: null, message: '' }
  chips.value = [...chips.value, chip]
  if (!(ALLOWED_FILE_TYPES as readonly string[]).includes(file.type)) {
    chipWith(chip.id, { state: 'failed', message: t('case.files.type') })
    return
  }
  if (file.size > MAX_FILE_BYTES) {
    chipWith(chip.id, { state: 'failed', message: t('case.files.tooLarge', { max: MAX_FILE_BYTES / (1024 * 1024) }) })
    return
  }
  const result = await props.upload(file)
  if (result.ok) {
    chipWith(chip.id, { state: 'ready', attachmentId: result.data.attachmentId })
    announcement.value = t('case.files.attached', { name: file.name })
  } else {
    chipWith(chip.id, { state: 'failed', message: refusalOf(result.error.status, result.error.message) })
    announcement.value = refusalOf(result.error.status, result.error.message)
  }
}

function addFiles(files: Iterable<File> | ArrayLike<File> | null | undefined): void {
  if (!files) return
  for (const file of Array.from(files)) void stage(file)
}

function onPick(event: Event): void {
  const input = event.target as HTMLInputElement
  addFiles(input.files)
  // Choosing the same file again must fire a change event too.
  input.value = ''
}

function onDrop(event: DragEvent): void {
  dragging.value = false
  if (!props.upload) return
  if (!event.dataTransfer?.files?.length) return
  event.preventDefault()
  addFiles(event.dataTransfer.files)
}

function onPaste(event: ClipboardEvent): void {
  if (!props.upload) return
  const files = Array.from(event.clipboardData?.items ?? [])
    .filter((item) => item.kind === 'file')
    .map((item) => item.getAsFile())
    .filter((file): file is File => file !== null)
  if (files.length === 0) return
  event.preventDefault()
  addFiles(files)
}

function remove(chip: Chip): void {
  chips.value = chips.value.filter((current) => current.id !== chip.id)
  announcement.value = t('case.files.removed', { name: chip.name })
}

async function submit(): Promise<void> {
  if (!canSend.value) {
    if (!saving.value && !valid.value) {
      error.value = length.value > MAX_CASE_MESSAGE_LENGTH ? t('case.conversation.tooLong', { max: MAX_CASE_MESSAGE_LENGTH }) : t('case.conversation.emptyInput')
    }
    return
  }
  saving.value = true
  error.value = ''
  const result = await props.save(text.value, key, readyIds.value)
  saving.value = false
  if (!result.ok) {
    // The server's own words ("no UUIDs or IP addresses") are the useful ones.
    error.value = result.error.message
    return
  }
  if (props.initial === undefined) {
    text.value = ''
    chips.value = []
    key = crypto.randomUUID()
  }
  emit('saved')
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
    event.preventDefault()
    void submit()
  }
}
</script>

<template>
  <form
    class="flex flex-col gap-2 rounded-md-md"
    :class="dragging ? 'outline-2 outline-dashed outline-primary' : ''"
    data-testid="message-composer"
    @submit.prevent="submit"
  >
    <div
      class="flex flex-col gap-2"
      data-testid="composer-drop"
      @dragenter.prevent="dragging = true"
      @dragover.prevent="dragging = true"
      @dragleave="dragging = false"
      @drop="onDrop"
    >
      <label :for="id" class="text-label-large text-on-surface-variant">{{ label }}</label>
      <textarea
        :id="id"
        v-model="text"
        rows="4"
        :disabled="saving"
        :aria-invalid="error ? 'true' : undefined"
        :aria-describedby="`${id}-meta`"
        class="w-full rounded-md-xs border bg-transparent p-3 text-body-large text-on-surface outline-none focus:border-2 focus:-outline-offset-2 focus:outline-2 focus:outline-primary"
        :class="error ? 'border-error border-2' : 'border-outline hover:border-on-surface'"
        data-testid="message-text"
        @keydown="onKeydown"
        @paste="onPaste"
      />
      <div :id="`${id}-meta`" class="flex flex-wrap items-center gap-2 text-body-small text-on-surface-variant">
        <span data-testid="message-counter" :class="length > MAX_CASE_MESSAGE_LENGTH ? 'text-error' : ''">{{ length }} / {{ MAX_CASE_MESSAGE_LENGTH }}</span>
        <span>{{ t('case.conversation.hint') }}</span>
      </div>
      <p data-testid="format-hint" class="text-body-small text-on-surface-variant">{{ t('case.conversation.formatHint') }}</p>

      <div v-if="upload" class="flex flex-col gap-2">
        <div class="flex flex-wrap items-center gap-2">
          <input
            ref="fileInput"
            type="file"
            multiple
            class="sr-only"
            :accept="ALLOWED_FILE_TYPES.join(',')"
            :aria-label="t('case.files.pick')"
            data-testid="file-input"
            @change="onPick"
          />
          <MdButton variant="tonal" data-testid="attach" :aria-describedby="`${id}-files`" @click="fileInput?.click()">{{ t('case.files.attach') }}</MdButton>
          <span :id="`${id}-files`" class="text-body-small text-on-surface-variant" data-testid="file-counter">{{ t('case.files.counter', { n: chips.length, max: MAX_FILES }) }}</span>
          <span class="text-body-small text-on-surface-variant">{{ t('case.files.dropHint') }}</span>
        </div>
        <p v-if="chips.length > 0" role="note" class="rounded-md-md bg-tertiary-container p-3 text-body-medium text-on-tertiary-container" data-testid="file-warning">
          {{ t('case.files.warning') }}
        </p>
        <ul v-if="chips.length > 0" class="flex flex-wrap gap-2" data-testid="chips">
          <li
            v-for="chip in chips"
            :key="chip.id"
            class="flex max-w-full items-center gap-2 rounded-md-full border border-outline px-3 py-1 text-body-small"
            :class="chip.state === 'failed' ? 'border-error text-error' : ''"
            :data-state="chip.state"
            data-testid="chip"
          >
            <span class="break-all font-medium">{{ chip.name }}</span>
            <span>{{ sizeLabel(chip.size) }}</span>
            <span v-if="chip.state === 'uploading'">{{ t('case.files.uploading') }}</span>
            <span v-else-if="chip.state === 'ready'">{{ t('case.files.ready') }}</span>
            <span v-else>{{ chip.message }}</span>
            <MdButton variant="text" :aria-label="t('case.files.remove', { name: chip.name })" data-testid="chip-remove" @click="remove(chip)">
              {{ t('case.files.removeLabel') }}
            </MdButton>
          </li>
        </ul>
      </div>
    </div>

    <p role="status" aria-live="polite" class="sr-only" data-testid="composer-status">{{ announcement }}</p>
    <p v-if="error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container" data-testid="message-error">{{ error }}</p>
    <div class="flex gap-2">
      <MdButton type="submit" :disabled="saving || filesBusy" data-testid="message-submit">{{ submitLabel }}</MdButton>
      <MdButton v-if="cancellable" variant="text" data-testid="message-cancel" @click="emit('cancel')">{{ t('actions.cancel') }}</MdButton>
    </div>
  </form>
</template>
