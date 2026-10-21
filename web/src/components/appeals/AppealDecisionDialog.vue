<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { Appeal, AppealDecision } from '../../api/types'
import MdButton from '../md/MdButton.vue'
import MdDialog from '../md/MdDialog.vue'
import MdSelect from '../md/MdSelect.vue'
import MdTextField from '../md/MdTextField.vue'

const props = defineProps<{ open: boolean; appeal: Appeal | null }>()
const emit = defineEmits<{ close: []; done: [message: string] }>()

const { t } = useI18n()
const api = useApi()

const decision = ref('')
const note = ref('')
const newEnd = ref('')
const endError = ref('')
const error = ref('')
const saving = ref(false)

const options = computed(() => [
  { value: '', label: t('appeals.decide.choose') },
  ...(['GRANTED_FULL_LIFT', 'GRANTED_DURATION_REDUCTION', 'DENIED'] as const).map((value) => ({
    value,
    label: t(`appeals.decide.decision.${value}`),
  })),
])

watch(
  () => props.open,
  (open) => {
    if (!open) return
    decision.value = ''
    note.value = ''
    newEnd.value = ''
    endError.value = ''
    error.value = ''
  },
  { immediate: true },
)

/** The backend answers an appeal review with a bare status (and sometimes plain text); explain the common ones. */
function explain(status: number, message: string): string {
  if (status === 403) return t('appeals.errors.selfReview')
  if (status === 409) return t('appeals.errors.notAwaiting')
  if (status === 404) return t('appeals.errors.notFound')
  return message
}

async function confirm(): Promise<void> {
  if (!props.appeal || !decision.value || saving.value) return
  const reduction = decision.value === 'GRANTED_DURATION_REDUCTION'
  const end = reduction && newEnd.value ? new Date(newEnd.value).getTime() : undefined
  endError.value = ''
  if (reduction && (end === undefined || Number.isNaN(end))) {
    endError.value = t('appeals.decide.endRequired')
    return
  }

  saving.value = true
  error.value = ''
  const result = await api.reviewAppeal(props.appeal.identifier, {
    decision: decision.value as AppealDecision['decision'],
    note: note.value.trim() || undefined,
    newExpirationAt: end,
  })
  saving.value = false
  if (!result.ok) {
    error.value = explain(result.error.status, result.error.message)
    return
  }
  emit('done', t('appeals.decided'))
}
</script>

<template>
  <MdDialog :open="open" :headline="t('appeals.decide.title')" @close="emit('close')">
    <div class="flex flex-col gap-5 pt-2">
      <p v-if="appeal" class="text-body-medium text-on-surface-variant">{{ appeal.statement }}</p>
      <MdSelect v-model="decision" :label="t('appeals.decide.decisionLabel')" :options="options" />
      <MdTextField
        v-if="decision === 'GRANTED_DURATION_REDUCTION'"
        v-model="newEnd"
        type="datetime-local"
        required
        :label="t('appeals.decide.newEnd')"
        :error="endError"
        :supporting-text="t('appeals.decide.newEndHint')"
      />
      <MdTextField v-model="note" :label="t('appeals.decide.note')" :supporting-text="t('appeals.decide.noteHint')" />
      <p v-if="error" data-testid="error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">
        {{ error }}
      </p>
    </div>
    <template #actions>
      <MdButton variant="text" data-testid="cancel" @click="emit('close')">{{ t('actions.cancel') }}</MdButton>
      <MdButton data-testid="confirm" :disabled="!decision || saving" @click="confirm">{{ t('appeals.decide.confirm') }}</MdButton>
    </template>
  </MdDialog>
</template>
