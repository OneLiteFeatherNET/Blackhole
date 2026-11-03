<script setup lang="ts">
// Starts a previewed rollback: the routed dialog `/admin/rollback/:id/execute` over the job. Opening it reads the job and
// sends nothing; only the confirm button starts it, with the reason and the two options. The start carries an
// Idempotency-Key that is kept until it succeeds, so a retry after a network failure answers the same job.
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { ApiError } from '../../api/types'
import { newIdempotencyKey } from '../kick/kickTargets'
import RoutedDialog from '../RoutedDialog.vue'
import { useRoutedDialog } from '../../router/routedDialog'
import MdButton from '../md/MdButton.vue'
import MdCheckbox from '../md/MdCheckbox.vue'
import MdTextField from '../md/MdTextField.vue'
import { rollbackErrorKey } from './rollback'

const props = defineProps<{ id: string }>()
const REASON_MAX = 500

const { t } = useI18n()
const api = useApi()
const dialog = useRoutedDialog({ parent: 'admin-rollback-job', child: 'admin-rollback-execute' })

const pending = ref<number | null>(null)
const notFound = ref(false)
const reason = ref('')
const suspendLink = ref(false)
const includeMarks = ref(false)
const busy = ref(false)
const error = ref('')
let key: string | null = null

const reasonTrimmed = computed(() => reason.value.trim())
const reasonError = computed(() => (reason.value.length > REASON_MAX ? t('rollback.reason.tooLong') : ''))
const canStart = computed(() => reasonTrimmed.value !== '' && reasonError.value === '' && !busy.value && pending.value !== null)

function errorText(failure: ApiError): string {
  const key = rollbackErrorKey(failure)
  return key ? t(key) : failure.message
}

onMounted(async () => {
  const result = await api.rollbackProgress(props.id)
  if (result.ok) pending.value = result.data.items?.PENDING ?? 0
  else if (result.error.kind === 'NOT_FOUND') notFound.value = true
  else error.value = errorText(result.error)
})

async function start(): Promise<void> {
  if (!canStart.value) return
  busy.value = true
  error.value = ''
  key ??= newIdempotencyKey()
  const result = await api.executeRollback(props.id, { reason: reasonTrimmed.value, suspendLink: suspendLink.value, includeMarks: includeMarks.value }, key)
  busy.value = false
  if (!result.ok) {
    // The key stays: a retry with it answers the same job and writes nothing twice.
    error.value = errorText(result.error)
    return
  }
  key = null
  await dialog.close()
}
</script>

<template>
  <RoutedDialog parent="admin-rollback-job" child="admin-rollback-execute" :headline="t('rollback.jobs.executeTitle')" :not-found="notFound">
    <div class="flex flex-col gap-4" data-testid="rollback-execute-dialog">
      <p v-if="pending !== null" data-testid="rollback-execute-counts">{{ t('rollback.jobs.pending', { count: pending }) }}</p>
      <p class="text-body-medium">{{ t('rollback.confirm.manual') }}</p>
      <div class="flex flex-col gap-3">
        <MdCheckbox
          v-model="includeMarks"
          :label="t('rollback.options.marksAll')"
          :supporting-text="t('rollback.options.marksHint')"
          data-testid="option-marks"
        />
        <MdCheckbox v-model="suspendLink" :label="t('rollback.options.suspend')" :supporting-text="t('rollback.options.suspendHint')" data-testid="option-suspend" />
      </div>
      <MdTextField
        v-model="reason"
        multiline
        :rows="2"
        required
        :label="t('rollback.reason.label')"
        :error="reasonError"
        :supporting-text="t('rollback.reason.hint')"
        data-testid="rollback-reason"
      />
      <p v-if="error" role="alert" data-testid="error" class="rounded-md-md bg-error-container p-3 text-on-error-container">{{ error }}</p>
      <div class="flex flex-wrap gap-2">
        <MdButton variant="error" :disabled="!canStart" data-testid="rollback-execute" @click="start">{{ t('rollback.confirm.submit') }}</MdButton>
      </div>
    </div>
  </RoutedDialog>
</template>
