<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import { MAX_NOTE_LENGTH } from '../../api/blackhole'
import type { EloSectionScore } from '../../api/types'
import MdButton from '../md/MdButton.vue'
import MdDialog from '../md/MdDialog.vue'
import MdTextField from '../md/MdTextField.vue'
import { previewAdjustment } from './adjustPreview'

const props = defineProps<{
  open: boolean
  owner: string
  entry: EloSectionScore
  /** While a permanent punishment is in force, a score change decides no further punishment. */
  hasActivePermanent: boolean
}>()
const emit = defineEmits<{ close: []; done: [message: string] }>()

const { t } = useI18n()
const api = useApi()

const delta = ref('')
const note = ref('')
const deltaError = ref('')
const noteError = ref('')
const error = ref('')
const saving = ref(false)

watch(
  () => props.open,
  (open) => {
    if (!open) return
    delta.value = ''
    note.value = ''
    deltaError.value = ''
    noteError.value = ''
    error.value = ''
  },
)

// A number input makes v-model emit a number once the text parses as one.
const deltaText = computed(() => String(delta.value).trim())
const parsedDelta = computed(() => (/^[+-]?\d+$/.test(deltaText.value) ? Number(deltaText.value) : null))
const effect = computed(() =>
  parsedDelta.value === null || parsedDelta.value === 0
    ? null
    : previewAdjustment(
        props.entry.score,
        parsedDelta.value,
        props.entry.softThreshold,
        props.entry.permanentThreshold,
        props.hasActivePermanent,
      ),
)

async function confirm(): Promise<void> {
  deltaError.value = parsedDelta.value === null || parsedDelta.value === 0 ? t('actions.adjust.invalidDelta') : ''
  noteError.value = note.value.trim() ? '' : t('actions.adjust.noteRequired')
  if (deltaError.value || noteError.value || parsedDelta.value === null || saving.value) return

  saving.value = true
  error.value = ''
  const result = await api.adjustElo(props.owner, {
    section: props.entry.section,
    delta: parsedDelta.value,
    note: note.value.trim(),
  })
  saving.value = false
  if (!result.ok) {
    error.value = result.error.message
    return
  }
  const after = result.data.scoreAfter
  emit(
    'done',
    result.data.escalation
      ? t('actions.adjust.doneEscalated', { after, section: props.entry.section })
      : t('actions.adjust.done', { after }),
  )
}
</script>

<template>
  <MdDialog :open="open" :headline="t('actions.adjust.title', { section: entry.section })" @close="emit('close')">
    <form class="flex flex-col gap-5 pt-2" novalidate @submit.prevent="confirm">
      <MdTextField
        v-model="delta"
        type="number"
        required
        :label="t('actions.adjust.delta')"
        :supporting-text="t('actions.adjust.deltaHint')"
        :error="deltaError"
      />
      <MdTextField
        v-model="note"
        required
        :label="t('actions.adjust.note')"
        :supporting-text="t('actions.adjust.noteHint', { max: MAX_NOTE_LENGTH })"
        :error="noteError"
      />
      <div v-if="effect" class="flex flex-col gap-2 text-on-surface">
        <p data-testid="preview">
          {{ t('actions.adjust.preview', { section: entry.section, before: entry.score, after: effect.scoreAfter }) }}
        </p>
        <p
          v-if="effect.outcome === 'permanent'"
          data-testid="permanent-warning"
          role="alert"
          class="rounded-md-md bg-error-container p-3 text-on-error-container"
        >
          {{
            t('actions.adjust.permanent', {
              section: entry.section,
              after: effect.scoreAfter,
              threshold: entry.permanentThreshold,
            })
          }}
        </p>
        <p
          v-else-if="effect.outcome === 'soft'"
          data-testid="soft-note"
          class="rounded-md-md bg-tertiary-container p-3 text-on-tertiary-container"
        >
          {{ t('actions.adjust.soft', { section: entry.section, threshold: entry.softThreshold }) }}
        </p>
      </div>
      <p v-if="error" data-testid="error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">
        {{ error }}
      </p>
    </form>
    <template #actions>
      <MdButton variant="text" data-testid="cancel" @click="emit('close')">{{ t('actions.cancel') }}</MdButton>
      <MdButton data-testid="confirm" :disabled="saving" @click="confirm">{{ t('actions.adjust.confirm') }}</MdButton>
    </template>
  </MdDialog>
</template>
