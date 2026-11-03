<script setup lang="ts">
// `/cases/:reference/messages/:messageId/redact`: removes the text of a message, ADMIN only. The address only opens the
// confirmation; nothing is removed on navigation, back or reload. A message that is not there shows not-found.
import { inject, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { useApi } from '../../api/inject'
import type { NoteRedactionReason } from '../../api/types'
import { useRoutedDialog } from '../../router/routedDialog'
import RoutedDialog from '../RoutedDialog.vue'
import { CASE_ITEMS_KEY } from './caseContext'
import { findMessage } from './caseItems'
import ReasonDialog from './ReasonDialog.vue'

const REDACTION_REASONS: NoteRedactionReason[] = ['PERSONAL_DATA', 'ERASURE_REQUEST', 'INAPPROPRIATE', 'OTHER']

const { t } = useI18n()
const api = useApi()
const route = useRoute()
const items = inject(CASE_ITEMS_KEY)!
const dialog = useRoutedDialog({ parent: 'case', child: 'case-message-redact' })

const reference = String(route.params.reference)
const messageId = String(route.params.messageId)
const checked = ref(false)
const missing = ref(false)

onMounted(async () => {
  const found = await findMessage(api, reference, messageId)
  checked.value = true
  missing.value = found.kind !== 'found'
})

async function done() {
  await items.changed(t('case.conversation.redactDone'))
  await dialog.close()
}
</script>

<template>
  <RoutedDialog v-if="checked && missing" parent="case" child="case-message-redact" :headline="t('case.conversation.redactTitle')" :not-found="true" />
  <ReasonDialog
    v-else-if="checked"
    :open="dialog.open.value"
    name="redact-message"
    :headline="t('case.conversation.redactTitle')"
    :body="t('case.conversation.redactBody')"
    :confirm-label="t('case.conversation.redactConfirm')"
    :reasons="REDACTION_REASONS"
    reason-prefix="case.redact.reason"
    :run="(reason: string) => api.redactCaseMessage(reference, messageId, reason as NoteRedactionReason)"
    @close="dialog.close"
    @done="done"
  />
</template>
