<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { Evidence, EvidenceDeleteReason } from '../../api/types'
import MdButton from '../md/MdButton.vue'
import MdDialog from '../md/MdDialog.vue'
import MdSelect from '../md/MdSelect.vue'
import MdTextField from '../md/MdTextField.vue'
import { kindInfo } from './evidenceKind'

const props = defineProps<{ open: boolean; item: Evidence | null }>()
const emit = defineEmits<{ close: []; done: [message: string] }>()

const { t } = useI18n()
const api = useApi()

const REASONS: EvidenceDeleteReason[] = ['ERASURE_REQUEST', 'WRONG_EVIDENCE', 'OTHER']

const reason = ref('')
const note = ref('')
const error = ref('')
const deleting = ref(false)

const reasons = computed(() => REASONS.map((value) => ({ value, label: t(`evidence.delete.reason.${value}`) })))

watch(
  () => props.open,
  (open) => {
    if (!open) return
    reason.value = ''
    note.value = ''
    error.value = ''
  },
  { immediate: true },
)

async function confirm(): Promise<void> {
  if (!props.item || !reason.value || deleting.value) return
  deleting.value = true
  error.value = ''
  const result = await api.deleteEvidence(props.item.identifier, { reason: reason.value as EvidenceDeleteReason, note: note.value })
  deleting.value = false
  if (!result.ok) {
    error.value = result.error.message
    return
  }
  emit('done', t('evidence.delete.done'))
}
</script>

<template>
  <MdDialog :open="open" :headline="t('evidence.delete.title')" @close="emit('close')">
    <div class="flex flex-col gap-5 pt-2">
      <p v-if="item">{{ t('evidence.delete.body', { kind: t(kindInfo(item.kind).labelKey) }) }}</p>
      <MdSelect v-model="reason" required :placeholder="t('evidence.delete.choose')" :label="t('evidence.delete.reasonLabel')" :options="reasons" />
      <MdTextField v-model="note" :label="t('evidence.delete.note')" :supporting-text="t('evidence.delete.noteHint')" />
      <p v-if="error" data-testid="error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">{{ error }}</p>
    </div>
    <template #actions>
      <MdButton variant="text" data-testid="cancel" @click="emit('close')">{{ t('actions.cancel') }}</MdButton>
      <MdButton data-testid="confirm" :disabled="!reason || deleting" @click="confirm">{{ t('evidence.delete.confirm') }}</MdButton>
    </template>
  </MdDialog>
</template>
