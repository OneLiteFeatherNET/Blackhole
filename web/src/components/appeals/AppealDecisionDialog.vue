<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { Appeal, AppealDecision } from '../../api/types'
import { MAX_CASE_MESSAGE_LENGTH } from '../case/conversation'
import EvidencePanel from '../evidence/EvidencePanel.vue'
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
const playerMessage = ref('')
const messageError = ref('')
const messageId = useId()
const newEnd = ref('')
const endError = ref('')
const error = ref('')
const saving = ref(false)

const options = computed(() => [
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
    playerMessage.value = ''
    messageError.value = ''
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

  const message = playerMessage.value.trim()
  messageError.value = ''
  if (message.length > MAX_CASE_MESSAGE_LENGTH) {
    messageError.value = t('case.conversation.tooLong', { max: MAX_CASE_MESSAGE_LENGTH })
    return
  }

  saving.value = true
  error.value = ''
  const result = await api.reviewAppeal(props.appeal.identifier, {
    decision: decision.value as AppealDecision['decision'],
    note: note.value.trim() || undefined,
    newExpirationAt: end,
    playerMessage: message || undefined,
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
  <MdDialog :open="open" wide :headline="t('appeals.decide.title')" @close="emit('close')">
    <div class="flex flex-col gap-5 pt-2">
      <p v-if="appeal" class="text-body-medium text-on-surface-variant">{{ appeal.statement }}</p>
      <!-- Read-only: the reviewer sees the evidence and can remove wrong items, but adds none. -->
      <EvidencePanel v-if="appeal" :key="appeal.identifier" :punishment="appeal.punishmentIdentifier" readonly />
      <MdSelect v-model="decision" required :placeholder="t('appeals.decide.choose')" :label="t('appeals.decide.decisionLabel')" :options="options" />
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
      <div class="flex flex-col gap-1">
        <label :for="messageId" class="text-label-large text-on-surface-variant">{{ t('appeals.decide.playerMessage') }}</label>
        <textarea
          :id="messageId"
          v-model="playerMessage"
          rows="3"
          :aria-invalid="messageError ? 'true' : undefined"
          :aria-describedby="`${messageId}-hint`"
          class="w-full rounded-md-xs border bg-transparent p-3 text-body-large text-on-surface outline-none focus:border-2 focus:-outline-offset-2 focus:outline-2 focus:outline-primary"
          :class="messageError ? 'border-error border-2' : 'border-outline hover:border-on-surface'"
          data-testid="player-message"
        />
        <p :id="`${messageId}-hint`" class="text-body-small" :class="messageError ? 'text-error' : 'text-on-surface-variant'" :role="messageError ? 'alert' : undefined" data-testid="player-message-hint">
          {{ messageError || t('appeals.decide.playerMessageHint') }}
        </p>
        <span class="text-body-small text-on-surface-variant" data-testid="player-message-counter">{{ playerMessage.length }} / {{ MAX_CASE_MESSAGE_LENGTH }}</span>
      </div>
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
