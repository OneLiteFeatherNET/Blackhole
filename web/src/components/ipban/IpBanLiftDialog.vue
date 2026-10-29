<script setup lang="ts">
// Lifts one active ban. The reason is mandatory: submit stays disabled until one is typed, and the field says why.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { IpBan } from '../../api/types'
import MdButton from '../md/MdButton.vue'
import MdDialog from '../md/MdDialog.vue'
import MdTextField from '../md/MdTextField.vue'
import { REASON_MAX, ipBanErrorKey, validateLiftReason } from './ipBanRules'

const props = defineProps<{ open: boolean; ban: IpBan | null }>()
const emit = defineEmits<{ close: []; done: [message: string] }>()

const { t } = useI18n()
const api = useApi()

const reason = ref('')
const saving = ref(false)
const error = ref('')

const problem = computed(() => validateLiftReason(reason.value))
const canSubmit = computed(() => problem.value === null && !saving.value && props.ban !== null)
const reasonHint = computed(() => {
  if (problem.value === 'empty') return t('ipBans.lift.reasonRequired')
  if (problem.value === 'tooLong') return t('ipBans.validation.reason.tooLong')
  return t('ipBans.lift.reasonHint', { max: REASON_MAX })
})

// A fresh reason each time a ban is opened for lifting.
watch(
  () => props.open,
  (open) => {
    if (!open) return
    reason.value = ''
    error.value = ''
  },
)

async function submit(): Promise<void> {
  if (!props.ban || !canSubmit.value) return
  saving.value = true
  error.value = ''
  const result = await api.liftIpBan(props.ban.id, reason.value)
  saving.value = false
  if (!result.ok) {
    const key = ipBanErrorKey(result.error, 'lift')
    error.value = key ? t(`ipBans.errors.${key}`) : result.error.message
    return
  }
  emit('done', t('ipBans.lift.done'))
}
</script>

<template>
  <MdDialog :open="open" :headline="t('ipBans.lift.title')" @close="emit('close')">
    <div class="flex flex-col gap-4 pt-2">
      <p class="text-body-medium text-on-surface-variant">{{ t('ipBans.lift.body') }}</p>
      <MdTextField
        v-model="reason"
        data-testid="lift-reason"
        required
        :label="t('ipBans.lift.reason')"
        :supporting-text="reasonHint"
        :error="reason !== '' && problem === 'tooLong' ? reasonHint : ''"
      />
      <p v-if="error" role="alert" data-testid="error" class="rounded-md-md bg-error-container p-3 text-on-error-container">{{ error }}</p>
    </div>
    <template #actions>
      <MdButton variant="text" data-testid="cancel" @click="emit('close')">{{ t('actions.cancel') }}</MdButton>
      <MdButton data-testid="confirm" :disabled="!canSubmit" @click="submit">{{ t('ipBans.lift.submit') }}</MdButton>
    </template>
  </MdDialog>
</template>
