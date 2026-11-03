<script setup lang="ts">
// Cancels a rollback: the routed dialog `/admin/rollback/:id/cancel` over the job. Opening it sends nothing; the confirm
// button cancels. A job that already ended answers 409 and the dialog says so.
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import RoutedDialog from '../RoutedDialog.vue'
import { useRoutedDialog } from '../../router/routedDialog'
import MdButton from '../md/MdButton.vue'
import { rollbackErrorKey } from './rollback'
import type { ApiError } from '../../api/types'

const props = defineProps<{ id: string }>()

const { t } = useI18n()
const api = useApi()
const dialog = useRoutedDialog({ parent: 'admin-rollback-job', child: 'admin-rollback-cancel' })
const busy = ref(false)
const error = ref('')

function errorText(failure: ApiError): string {
  const key = rollbackErrorKey(failure)
  return key ? t(key) : failure.message
}

async function cancel(): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = ''
  const result = await api.cancelRollback(props.id)
  busy.value = false
  if (!result.ok) {
    error.value = result.error.kind === 'CONFLICT' ? t('rollback.jobs.alreadyEnded') : errorText(result.error)
    return
  }
  await dialog.close()
}
</script>

<template>
  <RoutedDialog parent="admin-rollback-job" child="admin-rollback-cancel" :headline="t('rollback.jobs.cancelTitle')">
    <div class="flex flex-col gap-4" data-testid="rollback-cancel-dialog">
      <p class="text-body-large">{{ t('rollback.jobs.cancelBody') }}</p>
      <p v-if="error" role="alert" data-testid="error" class="rounded-md-md bg-error-container p-3 text-on-error-container">{{ error }}</p>
      <div class="flex flex-wrap gap-2">
        <MdButton variant="error" :disabled="busy" data-testid="rollback-cancel-confirm" @click="cancel">{{ t('rollback.progress.cancel') }}</MdButton>
      </div>
    </div>
  </RoutedDialog>
</template>
