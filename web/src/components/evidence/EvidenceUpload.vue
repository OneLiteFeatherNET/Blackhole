<script setup lang="ts">
// Drag-and-drop zone and file picker. Files upload one after the other so the backend's temp space
// stays bounded. The type and size checks here are advisory: the server decides.
import { computed, onBeforeUnmount, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi, useEvidenceLimits } from '../../api/inject'
import type { Upload, UploadErrorKind } from '../../api/uploadEvidence'
import MdButton from '../md/MdButton.vue'
import MdProgress from '../md/MdProgress.vue'

const props = defineProps<{ punishment: string }>()
const emit = defineEmits<{ uploaded: [] }>()

type Problem = UploadErrorKind | 'CLIENT_TYPE' | 'CLIENT_SIZE'

interface Entry {
  id: number
  file: File
  state: 'queued' | 'uploading' | 'failed'
  /** 0..100 */
  progress: number
  problem: Problem | null
  /** The backend's explanation, shown next to the generic text. */
  detail: string
  upload: Upload | null
}

const { t } = useI18n()
const api = useApi()
const limits = useEvidenceLimits()

const entries = ref<Entry[]>([])
const dragging = ref(false)
const announcement = ref('')
const picker = ref<HTMLInputElement | null>(null)
let nextId = 0
let running = false
let unmounted = false

const accept = computed(() => limits.allowedTypes.join(','))

function megabytes(bytes: number): string {
  return String(Math.round((bytes / 1_048_576) * 10) / 10)
}

/** What can be told before sending; an unknown browser MIME type is left to the server. */
function precheck(file: File): Problem | null {
  const type = file.type.toLowerCase()
  if (type && !limits.allowedTypes.includes(type)) return 'CLIENT_TYPE'
  const cap = type.startsWith('video/')
    ? limits.maxVideoBytes
    : type
      ? limits.maxImageBytes
      : Math.max(limits.maxImageBytes, limits.maxVideoBytes)
  return file.size > cap ? 'CLIENT_SIZE' : null
}

function capOf(file: File): number {
  return file.type.toLowerCase().startsWith('video/') ? limits.maxVideoBytes : limits.maxImageBytes
}

function messageOf(entry: Entry): string {
  const problem = entry.problem ?? 'FAILED'
  const text = t(`evidence.upload.error.${problem}`, { max: megabytes(capOf(entry.file)) })
  return entry.detail ? `${text} (${entry.detail})` : text
}

function addFiles(files: File[]): void {
  for (const file of files) {
    const problem = precheck(file)
    entries.value.push({ id: nextId++, file, state: problem ? 'failed' : 'queued', progress: 0, problem, detail: '', upload: null })
    if (problem) announcement.value = t('evidence.upload.failedAnnounce', { name: file.name })
  }
  void pump()
}

async function pump(): Promise<void> {
  if (running) return
  running = true
  try {
    for (let entry = entries.value.find((e) => e.state === 'queued'); entry && !unmounted; entry = entries.value.find((e) => e.state === 'queued')) {
      await send(entry)
    }
  } finally {
    running = false
  }
}

async function send(entry: Entry): Promise<void> {
  entry.state = 'uploading'
  announcement.value = t('evidence.upload.startedAnnounce', { name: entry.file.name })
  const upload = api.startEvidenceUpload(props.punishment, entry.file, (fraction) => (entry.progress = Math.round(fraction * 100)))
  entry.upload = upload
  const result = await upload.result
  entry.upload = null
  if (result.ok) {
    remove(entry)
    announcement.value = t('evidence.upload.doneAnnounce', { name: entry.file.name })
    emit('uploaded')
  } else if (result.error.kind === 'CANCELLED') {
    remove(entry)
    announcement.value = t('evidence.upload.cancelledAnnounce', { name: entry.file.name })
  } else {
    entry.state = 'failed'
    entry.problem = result.error.kind
    entry.detail = result.error.message
    announcement.value = t('evidence.upload.failedAnnounce', { name: entry.file.name })
  }
}

function remove(entry: Entry): void {
  entries.value = entries.value.filter((e) => e.id !== entry.id)
}

/** Cancels a running upload, or drops a queued or failed entry. */
function dismiss(entry: Entry): void {
  if (entry.upload) entry.upload.cancel()
  else remove(entry)
}

function onDrop(event: DragEvent): void {
  dragging.value = false
  addFiles(Array.from(event.dataTransfer?.files ?? []))
}

function onPick(event: Event): void {
  const input = event.target as HTMLInputElement
  addFiles(Array.from(input.files ?? []))
  input.value = '' // picking the same file again must fire another change event
}

onBeforeUnmount(() => {
  unmounted = true
  entries.value.forEach((entry) => entry.upload?.cancel())
})
</script>

<template>
  <div class="flex flex-col gap-3">
    <div
      role="button"
      tabindex="0"
      data-testid="dropzone"
      :aria-label="t('evidence.upload.zone')"
      class="flex cursor-pointer flex-col items-center gap-1 rounded-md-md border-2 border-dashed p-6 text-center outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
      :class="dragging ? 'border-primary bg-primary-container text-on-primary-container' : 'border-outline text-on-surface-variant'"
      @click="picker?.click()"
      @keydown.enter.prevent="picker?.click()"
      @keydown.space.prevent="picker?.click()"
      @dragenter.prevent="dragging = true"
      @dragover.prevent="dragging = true"
      @dragleave="dragging = false"
      @drop.prevent="onDrop"
    >
      <span class="text-body-large">{{ t('evidence.upload.prompt') }}</span>
      <span class="text-body-small">{{ t('evidence.upload.hint', { image: megabytes(limits.maxImageBytes), video: megabytes(limits.maxVideoBytes) }) }}</span>
    </div>
    <input ref="picker" type="file" multiple :accept="accept" class="hidden" tabindex="-1" data-testid="picker" @change="onPick" />

    <ul v-if="entries.length > 0" class="flex flex-col gap-2">
      <li v-for="entry in entries" :key="entry.id" class="flex flex-col gap-1 rounded-md-sm bg-surface-container p-3" data-testid="upload-entry">
        <div class="flex items-center gap-2">
          <span class="min-w-0 flex-1 truncate text-body-medium">{{ entry.file.name }}</span>
          <MdButton variant="text" data-testid="dismiss" @click="dismiss(entry)">
            {{ entry.state === 'failed' ? t('evidence.upload.dismiss') : t('actions.cancel') }}
          </MdButton>
        </div>
        <MdProgress v-if="entry.state === 'uploading'" :label="t('evidence.upload.progress', { name: entry.file.name })" :value="entry.progress" />
        <span v-else-if="entry.state === 'queued'" class="text-body-small text-on-surface-variant">{{ t('evidence.upload.queued') }}</span>
        <p v-else role="alert" class="text-body-small text-error" data-testid="upload-error">{{ messageOf(entry) }}</p>
      </li>
    </ul>

    <!-- Announced politely when an upload starts, finishes or fails; the progress bars carry the percentage. -->
    <p class="sr-only" role="status" aria-live="polite" data-testid="announcement">{{ announcement }}</p>
  </div>
</template>
