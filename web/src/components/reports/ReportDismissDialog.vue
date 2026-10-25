<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { Report } from '../../api/types'
import MdButton from '../md/MdButton.vue'
import MdDialog from '../md/MdDialog.vue'
import MdTextField from '../md/MdTextField.vue'

// Dismisses a report through the resolve route. The note is required: it is what the next moderator reads
// to see why nothing was done. The reporter only learns that no action was taken, never the note.
const props = defineProps<{ open: boolean; report: Report | null }>()
const emit = defineEmits<{ close: []; done: [message: string] }>()

const { t } = useI18n()
const api = useApi()

const note = ref('')
const error = ref('')
const saving = ref(false)

watch(
  () => props.open,
  (open) => {
    if (!open) return
    note.value = ''
    error.value = ''
  },
  { immediate: true },
)

async function confirm(): Promise<void> {
  const reason = note.value.trim()
  if (!props.report?.identifier || reason === '' || saving.value) return
  saving.value = true
  error.value = ''
  const result = await api.resolveReport(props.report.identifier, { status: 'DISMISSED', note: reason })
  saving.value = false
  if (!result.ok) {
    error.value = result.error.message
    return
  }
  emit('done', t('reports.detail.dismissed'))
}
</script>

<template>
  <MdDialog :open="open" :headline="t('reports.detail.dismissTitle')" @close="emit('close')">
    <div class="flex flex-col gap-4 pt-2">
      <p class="text-body-medium text-on-surface-variant">{{ t('reports.detail.dismissHint') }}</p>
      <MdTextField v-model="note" required :label="t('reports.detail.dismissNote')" :supporting-text="t('reports.detail.dismissNoteHint')" />
      <p v-if="error" data-testid="error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">
        {{ error }}
      </p>
    </div>
    <template #actions>
      <MdButton variant="text" data-testid="cancel" @click="emit('close')">{{ t('actions.cancel') }}</MdButton>
      <MdButton data-testid="confirm" :disabled="note.trim() === '' || saving" @click="confirm">{{ t('reports.detail.dismissConfirm') }}</MdButton>
    </template>
  </MdDialog>
</template>
