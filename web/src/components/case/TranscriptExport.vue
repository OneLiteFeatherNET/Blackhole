<script setup lang="ts">
// Admins download the transcript of the case. Exporting is recorded on the case by the backend, so
// nothing is fetched until the button is pressed. The file is handed to the browser as a download
// and never kept: not in the store, not in browser storage.
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { TranscriptFile, TranscriptFormat } from '../../api/types'
import MdButton from '../md/MdButton.vue'
import MdCheckbox from '../md/MdCheckbox.vue'
import MdSelect from '../md/MdSelect.vue'

const props = defineProps<{ reference: string; shortId: string }>()
const emit = defineEmits<{ exported: [message: string] }>()

const { t } = useI18n()
const api = useApi()

const format = ref<TranscriptFormat>('json')
const includeNotes = ref(false)
const busy = ref(false)
const error = ref('')

const formats = [
  { value: 'json', label: 'JSON' },
  { value: 'text', label: 'Text' },
]

function download(file: TranscriptFile): void {
  const blob = new Blob([file.content], { type: `${file.mediaType};charset=utf-8` })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `case-${props.shortId.replace(/^#/, '')}-transcript.${file.format === 'json' ? 'json' : 'txt'}`
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}

async function run(): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = ''
  const result = await api.exportCaseTranscript(props.reference, format.value, includeNotes.value)
  busy.value = false
  if (!result.ok) {
    error.value = result.error.message
    return
  }
  download(result.data)
  emit('exported', t('case.transcript.done'))
}
</script>

<template>
  <section :aria-label="t('case.transcript.title')" class="flex flex-col gap-3" data-testid="transcript-export">
    <h3 class="text-title-medium">{{ t('case.transcript.title') }}</h3>
    <p class="text-body-medium text-on-surface-variant">{{ t('case.transcript.hint') }}</p>
    <div class="flex flex-wrap items-start gap-4">
      <MdSelect v-model="format" :label="t('case.transcript.formatLabel')" :options="formats" data-testid="transcript-format" />
      <MdCheckbox v-model="includeNotes" :label="t('case.transcript.includeNotes')" :supporting-text="t('case.transcript.includeNotesHint')" data-testid="transcript-notes" />
    </div>
    <div>
      <MdButton variant="outlined" :disabled="busy" data-testid="transcript-download" @click="run">{{ t('case.transcript.download') }}</MdButton>
    </div>
    <p v-if="error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container" data-testid="transcript-error">{{ error }}</p>
  </section>
</template>
