<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { NoteRedactionReason } from '../../api/types'
import MdButton from '../md/MdButton.vue'
import MdDialog from '../md/MdDialog.vue'
import MdSelect from '../md/MdSelect.vue'

const props = defineProps<{ open: boolean; reference: string; noteId: string | null }>()
const emit = defineEmits<{ close: []; done: [message: string] }>()

const { t } = useI18n()
const api = useApi()

const REASONS: NoteRedactionReason[] = ['PERSONAL_DATA', 'ERASURE_REQUEST', 'INAPPROPRIATE', 'OTHER']

const reason = ref('')
const error = ref('')
const saving = ref(false)

const reasons = computed(() => REASONS.map((value) => ({ value, label: t(`case.redact.reason.${value}`) })))

watch(
  () => props.open,
  (open) => {
    if (!open) return
    reason.value = ''
    error.value = ''
  },
  { immediate: true },
)

async function confirm(): Promise<void> {
  if (!props.noteId || !reason.value || saving.value) return
  saving.value = true
  error.value = ''
  const result = await api.redactCaseNote(props.reference, props.noteId, reason.value as NoteRedactionReason)
  saving.value = false
  if (!result.ok) {
    error.value = result.error.message
    return
  }
  emit('done', t('case.redact.done'))
}
</script>

<template>
  <MdDialog :open="open" :headline="t('case.redact.title')" @close="emit('close')">
    <div class="flex flex-col gap-5 pt-2">
      <p class="text-body-medium text-on-surface-variant">{{ t('case.redact.body') }}</p>
      <MdSelect v-model="reason" required :placeholder="t('case.redact.choose')" :label="t('case.redact.reasonLabel')" :options="reasons" />
      <p v-if="error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container" data-testid="redact-error">{{ error }}</p>
    </div>
    <template #actions>
      <MdButton variant="text" data-testid="redact-cancel" @click="emit('close')">{{ t('actions.cancel') }}</MdButton>
      <MdButton data-testid="redact-confirm" :disabled="!reason || saving" @click="confirm">{{ t('case.redact.confirm') }}</MdButton>
    </template>
  </MdDialog>
</template>
