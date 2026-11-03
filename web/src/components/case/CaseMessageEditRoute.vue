<script setup lang="ts">
// `/cases/:reference/messages/:messageId/edit`: corrects a staff message in the panel. The text is read when the address
// opens; nothing is sent until the form is saved. A message that is gone, deleted, redacted or written in Discord shows
// not-found, never a form. The server still decides whether this caller may change it (its author or an admin).
import { inject, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { useApi } from '../../api/inject'
import type { ApiError } from '../../api/types'
import { useRoutedDialog } from '../../router/routedDialog'
import RoutedDialog from '../RoutedDialog.vue'
import ResourceState from '../ResourceState.vue'
import { CASE_ITEMS_KEY } from './caseContext'
import { findMessage, isPanelStaffMessage } from './caseItems'
import MessageComposer from './MessageComposer.vue'

const { t } = useI18n()
const api = useApi()
const route = useRoute()
const items = inject(CASE_ITEMS_KEY)!
const dialog = useRoutedDialog({ parent: 'case', child: 'case-message-edit' })

const reference = String(route.params.reference)
const messageId = String(route.params.messageId)
const loading = ref(true)
const missing = ref(false)
const failure = ref<ApiError | null>(null)
const text = ref('')

onMounted(async () => {
  const found = await findMessage(api, reference, messageId)
  loading.value = false
  if (found.kind === 'found' && isPanelStaffMessage(found.value)) text.value = found.value.text ?? ''
  else if (found.kind === 'failed') failure.value = found.error
  else missing.value = true
})

async function save(value: string) {
  const result = await api.editCaseMessage(reference, messageId, value)
  if (result.ok) {
    await items.changed(t('case.conversation.edited'))
    await dialog.close()
  }
  return result
}
</script>

<template>
  <RoutedDialog parent="case" child="case-message-edit" :headline="t('case.conversation.editTitle')" :not-found="missing">
    <ResourceState :loading="loading" :error="failure" :has-data="!loading && failure === null">
      <MessageComposer
        :label="t('case.conversation.editLabel')"
        :submit-label="t('case.conversation.save')"
        :initial="text"
        :save="save"
        cancellable
        @cancel="dialog.close"
      />
    </ResourceState>
  </RoutedDialog>
</template>
