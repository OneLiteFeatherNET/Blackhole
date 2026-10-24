<script setup lang="ts">
// Writes or edits one message to the player. The server decides what is acceptable (no UUID, IP
// address or Ban ID); the checks here only save a round trip. A failed send keeps the text and the
// idempotency key, so pressing send again can never post the message twice.
import { computed, ref, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ApiResult } from '../../api/types'
import MdButton from '../md/MdButton.vue'
import { MAX_CASE_MESSAGE_LENGTH } from './conversation'

const props = defineProps<{
  label: string
  submitLabel: string
  /** Receives the text and the idempotency key of this attempt. */
  save: (text: string, key: string) => Promise<ApiResult<unknown>>
  initial?: string
  /** Offers a cancel button (editing an existing message). */
  cancellable?: boolean
}>()
const emit = defineEmits<{ saved: []; cancel: [] }>()

const { t } = useI18n()
const id = useId()
const text = ref(props.initial ?? '')
const error = ref('')
const saving = ref(false)
let key = crypto.randomUUID()

const length = computed(() => text.value.length)
const valid = computed(() => text.value.trim().length > 0 && length.value <= MAX_CASE_MESSAGE_LENGTH)

async function submit(): Promise<void> {
  if (saving.value) return
  if (!valid.value) {
    error.value = length.value > MAX_CASE_MESSAGE_LENGTH ? t('case.conversation.tooLong', { max: MAX_CASE_MESSAGE_LENGTH }) : t('case.conversation.emptyInput')
    return
  }
  saving.value = true
  error.value = ''
  const result = await props.save(text.value, key)
  saving.value = false
  if (!result.ok) {
    // The server's own words ("no UUIDs or IP addresses") are the useful ones.
    error.value = result.error.message
    return
  }
  if (props.initial === undefined) {
    text.value = ''
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
  <form class="flex flex-col gap-2" data-testid="message-composer" @submit.prevent="submit">
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
    />
    <div :id="`${id}-meta`" class="flex flex-wrap items-center gap-2 text-body-small text-on-surface-variant">
      <span data-testid="message-counter" :class="length > MAX_CASE_MESSAGE_LENGTH ? 'text-error' : ''">{{ length }} / {{ MAX_CASE_MESSAGE_LENGTH }}</span>
      <span>{{ t('case.conversation.hint') }}</span>
    </div>
    <p v-if="error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container" data-testid="message-error">{{ error }}</p>
    <div class="flex gap-2">
      <MdButton type="submit" :disabled="saving" data-testid="message-submit">{{ submitLabel }}</MdButton>
      <MdButton v-if="cancellable" variant="text" data-testid="message-cancel" @click="emit('cancel')">{{ t('actions.cancel') }}</MdButton>
    </div>
  </form>
</template>
