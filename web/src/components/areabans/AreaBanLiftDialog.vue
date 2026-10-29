<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { AreaBan } from '../../api/types'
import MdButton from '../md/MdButton.vue'
import MdDialog from '../md/MdDialog.vue'
import MdTextField from '../md/MdTextField.vue'
import { reasonIsValid } from './areaBanRules'

// Lifting keeps the ban in the history. The reason is required, so nothing is sent while it is blank.
const props = defineProps<{ ban: AreaBan | null }>()
const emit = defineEmits<{ close: []; lifted: [] }>()

const { t } = useI18n()
const api = useApi()

const reason = ref('')
const error = ref('')
const saving = ref(false)
const canSubmit = computed(() => props.ban !== null && reasonIsValid(reason.value) && !saving.value)

watch(
  () => props.ban,
  (ban) => {
    if (!ban) return
    reason.value = ''
    error.value = ''
  },
  { immediate: true },
)

async function submit(): Promise<void> {
  if (!props.ban || !canSubmit.value) return
  saving.value = true
  error.value = ''
  const result = await api.liftAreaBan(props.ban.id, reason.value)
  saving.value = false
  if (!result.ok) {
    error.value = result.error.message
    return
  }
  emit('lifted')
}
</script>

<template>
  <MdDialog :open="ban !== null" :headline="t('areaBans.lift.title')" @close="emit('close')">
    <div class="flex flex-col gap-4">
      <p class="text-body-medium text-on-surface-variant">{{ t('areaBans.lift.body', { value: ban?.value ?? '' }) }}</p>
      <MdTextField
        v-model="reason"
        data-testid="lift-reason"
        required
        :label="t('areaBans.lift.reason')"
        :supporting-text="t('areaBans.lift.reasonHint')"
      />
      <p v-if="error" data-testid="error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">
        {{ error }}
      </p>
    </div>
    <template #actions>
      <MdButton variant="text" data-testid="cancel" @click="emit('close')">{{ t('actions.cancel') }}</MdButton>
      <MdButton data-testid="confirm" :disabled="!canSubmit" @click="submit">{{ t('areaBans.lift.confirm') }}</MdButton>
    </template>
  </MdDialog>
</template>
