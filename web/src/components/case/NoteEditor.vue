<script setup lang="ts">
// Writes or edits one staff note. The server decides what is acceptable (it rejects UUIDs and IP
// addresses); the checks here only save a round trip. A failed save keeps the text.
import { computed, ref, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ApiResult } from '../../api/types'
import MdButton from '../md/MdButton.vue'
import { MAX_CASE_NOTE_LENGTH } from './caseEvents'


const props = defineProps<{
  label: string
  submitLabel: string
  save: (text: string) => Promise<ApiResult<unknown>>
  initial?: string
  /** Offers a cancel button (editing an existing note). */
  cancellable?: boolean
}>()
const emit = defineEmits<{ saved: []; cancel: [] }>()

const { t } = useI18n()
const id = useId()
const text = ref(props.initial ?? '')
const error = ref('')
const saving = ref(false)

const length = computed(() => text.value.length)
const valid = computed(() => text.value.trim().length > 0 && length.value <= MAX_CASE_NOTE_LENGTH)

async function submit(): Promise<void> {
  if (saving.value) return
  if (!valid.value) {
    error.value = length.value > MAX_CASE_NOTE_LENGTH ? t('case.notes.tooLong', { max: MAX_CASE_NOTE_LENGTH }) : t('case.notes.empty')
    return
  }
  saving.value = true
  error.value = ''
  const result = await props.save(text.value)
  saving.value = false
  if (!result.ok) {
    // The server's own words ("no UUIDs or IP addresses") are the useful ones.
    error.value = result.error.message
    return
  }
  if (props.initial === undefined) text.value = ''
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
  <form class="flex flex-col gap-2" data-testid="note-editor" @submit.prevent="submit">
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
      data-testid="note-text"
      @keydown="onKeydown"
    />
    <div :id="`${id}-meta`" class="flex flex-wrap items-center gap-2 text-body-small text-on-surface-variant">
      <span data-testid="note-counter" :class="length > MAX_CASE_NOTE_LENGTH ? 'text-error' : ''">{{ length }} / {{ MAX_CASE_NOTE_LENGTH }}</span>
      <span>{{ t('case.notes.hint') }}</span>
    </div>
    <p v-if="error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container" data-testid="note-error">{{ error }}</p>
    <div class="flex gap-2">
      <MdButton type="submit" :disabled="saving" data-testid="note-submit">{{ submitLabel }}</MdButton>
      <MdButton v-if="cancellable" variant="text" data-testid="note-cancel" @click="emit('cancel')">{{ t('actions.cancel') }}</MdButton>
    </div>
  </form>
</template>
