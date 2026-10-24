<script setup lang="ts">
// A confirmation that needs one reason code (a closed list, never free text). The caller says what
// happens; a failed call keeps the dialog open and shows the server's words.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ApiResult } from '../../api/types'
import MdButton from '../md/MdButton.vue'
import MdDialog from '../md/MdDialog.vue'
import MdSelect from '../md/MdSelect.vue'

const props = defineProps<{
  open: boolean
  /** Prefix of the test ids: `<name>-confirm`, `<name>-cancel`, `<name>-error`. */
  name: string
  headline: string
  body: string
  confirmLabel: string
  reasons: readonly string[]
  /** i18n prefix of the reason labels, e.g. `case.banId.leakReason`. */
  reasonPrefix: string
  run: (reason: string) => Promise<ApiResult<unknown>>
}>()
const emit = defineEmits<{ close: []; done: [] }>()

const { t } = useI18n()

const reason = ref('')
const error = ref('')
const saving = ref(false)

const options = computed(() => props.reasons.map((value) => ({ value, label: t(`${props.reasonPrefix}.${value}`) })))

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
  if (!reason.value || saving.value) return
  saving.value = true
  error.value = ''
  const result = await props.run(reason.value)
  saving.value = false
  if (!result.ok) {
    error.value = result.error.message
    return
  }
  emit('done')
}
</script>

<template>
  <MdDialog :open="open" :headline="headline" @close="emit('close')">
    <div class="flex flex-col gap-5 pt-2">
      <p class="text-body-medium text-on-surface-variant">{{ body }}</p>
      <MdSelect v-model="reason" required :placeholder="t('case.reason.choose')" :label="t('case.reason.label')" :options="options" :data-testid="`${name}-reason`" />
      <p v-if="error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container" :data-testid="`${name}-error`">{{ error }}</p>
    </div>
    <template #actions>
      <MdButton variant="text" :data-testid="`${name}-cancel`" @click="emit('close')">{{ t('actions.cancel') }}</MdButton>
      <MdButton :data-testid="`${name}-confirm`" :disabled="!reason || saving" @click="confirm">{{ confirmLabel }}</MdButton>
    </template>
  </MdDialog>
</template>
