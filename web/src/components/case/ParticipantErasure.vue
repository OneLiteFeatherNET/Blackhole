<script setup lang="ts">
// Erases one Discord participant of the case (ADMIN): the stored Discord id and display name go, the
// entries stay with an anonymous author. A participant is shown as "Discord participant N" with its role
// in the conversation; the id the API takes is the opaque participant id, never a Discord id.
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import MdButton from '../md/MdButton.vue'
import MdDialog from '../md/MdDialog.vue'

export interface ErasableParticipant {
  id: string
  /** Already translated: what the participant was in the conversation (player, staff, bot). */
  role: string
}

const props = defineProps<{ reference: string; participants: ErasableParticipant[] }>()
const emit = defineEmits<{ done: [message: string] }>()

const { t } = useI18n()
const api = useApi()

const target = ref<ErasableParticipant | null>(null)
const targetNo = ref(0)
const error = ref('')
const saving = ref(false)
/** Erased in this view; a reload shows the anonymous author anyway. */
const erased = ref<string[]>([])

function ask(participant: ErasableParticipant, index: number): void {
  target.value = participant
  targetNo.value = index + 1
  error.value = ''
}

function close(): void {
  target.value = null
}

async function confirm(): Promise<void> {
  const participant = target.value
  if (!participant || saving.value) return
  saving.value = true
  error.value = ''
  const result = await api.eraseCaseParticipant(props.reference, participant.id)
  saving.value = false
  if (!result.ok) {
    error.value = result.error.message
    return
  }
  erased.value = [...erased.value, participant.id]
  target.value = null
  emit('done', t(result.data.erased ? 'case.participants.done' : 'case.participants.alreadyDone'))
}
</script>

<template>
  <section v-if="participants.length > 0" :aria-label="t('case.participants.title')" class="flex flex-col gap-2" data-testid="participants">
    <h4 class="text-title-small">{{ t('case.participants.title') }}</h4>
    <p class="text-body-medium text-on-surface-variant">{{ t('case.participants.hint') }}</p>
    <ul class="flex flex-col gap-1">
      <li v-for="(participant, index) in participants" :key="participant.id" class="flex flex-wrap items-center gap-2" :data-testid="`participant-${index + 1}`">
        <span class="text-body-large">{{ t('case.participants.name', { no: index + 1 }) }}</span>
        <span class="text-body-medium text-on-surface-variant">({{ participant.role }})</span>
        <span v-if="erased.includes(participant.id)" class="text-body-medium text-on-surface-variant" data-testid="participant-erased">{{ t('case.participants.erased') }}</span>
        <MdButton v-else variant="outlined" data-testid="participant-erase" @click="ask(participant, index)">{{ t('case.participants.erase') }}</MdButton>
      </li>
    </ul>

    <MdDialog :open="target !== null" :headline="t('case.participants.confirmTitle', { no: targetNo })" @close="close">
      <div class="flex flex-col gap-3 pt-2">
        <p class="text-body-medium text-on-surface-variant">{{ t('case.participants.confirmBody') }}</p>
        <p v-if="error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container" data-testid="participant-error">{{ error }}</p>
      </div>
      <template #actions>
        <MdButton variant="text" data-testid="participant-cancel" @click="close">{{ t('actions.cancel') }}</MdButton>
        <MdButton data-testid="participant-confirm" :disabled="saving" @click="confirm">{{ t('case.participants.confirm') }}</MdButton>
      </template>
    </MdDialog>
  </section>
</template>
