<script setup lang="ts">
// The client rule editor as a route: `/admin/client-rules/new` creates, `/admin/client-rules/:key/edit` edits. The
// editor opens only once the rule is read, so a missing one shows the not-found text and no empty form. Delete is
// offered on the edit route only; it asks first and sends nothing before the confirmation, then goes to the list.
import { inject, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useApi } from '../../api/inject'
import type { ClientRule } from '../../api/types'
import { useRoutedDialog } from '../../router/routedDialog'
import { CONFIG_PAGE, NO_CONFIG_PAGE } from '../config/configPage'
import RoutedDialog from '../RoutedDialog.vue'
import MdButton from '../md/MdButton.vue'
import MdDialog from '../md/MdDialog.vue'
import ClientRuleEditor from './ClientRuleEditor.vue'

const { t } = useI18n()
const api = useApi()
const route = useRoute()
const router = useRouter()
const page = inject(CONFIG_PAGE, NO_CONFIG_PAGE)

const editing = route.name === 'admin-client-rule-edit'
const parent = editing ? 'admin-client-rule' : 'admin-client-rules'
const child = editing ? 'admin-client-rule-edit' : 'admin-client-rule-new'
const dialog = useRoutedDialog({ parent, child })

const rule = ref<ClientRule | null>(null)
const ready = ref(!editing)
const missing = ref(false)
const confirming = ref(false)
const deleting = ref(false)
const deleteError = ref('')

if (editing) void load()

async function load(): Promise<void> {
  const result = await api.clientRule(String(route.params.key))
  if (result.ok) rule.value = result.data
  else missing.value = true
  ready.value = true
}

async function saved(message: string): Promise<void> {
  await page.changed(message)
  await dialog.close()
}

async function remove(): Promise<void> {
  if (!rule.value || deleting.value) return
  deleting.value = true
  const result = await api.deleteClientRule(rule.value.key)
  deleting.value = false
  if (!result.ok) {
    deleteError.value = result.error.message
    return
  }
  confirming.value = false
  await router.replace({ name: 'admin-client-rules' })
}
</script>

<template>
  <RoutedDialog v-if="missing" :parent="parent" :child="child" :headline="t('routedDialog.notFoundTitle')" notFound />
  <template v-else-if="ready">
    <ClientRuleEditor :open="dialog.open.value" :rule="rule" @close="dialog.close" @saved="saved">
      <template v-if="editing" #extra-actions>
        <MdButton variant="error" data-testid="delete-client-rule" @click="confirming = true">{{ t('clientRules.delete.open') }}</MdButton>
      </template>
    </ClientRuleEditor>
    <MdDialog v-if="rule" :open="confirming" :headline="t('clientRules.delete.title', { key: rule.key })" @close="confirming = false">
      <p>{{ t('clientRules.delete.body') }}</p>
      <p v-if="deleteError" data-testid="error" role="alert" class="mt-3 rounded-md-md bg-error-container p-3 text-on-error-container">
        {{ deleteError }}
      </p>
      <template #actions>
        <MdButton variant="text" data-testid="cancel-delete" @click="confirming = false">{{ t('actions.cancel') }}</MdButton>
        <MdButton variant="error" data-testid="confirm-delete" :disabled="deleting" @click="remove">{{ t('clientRules.delete.confirm') }}</MdButton>
      </template>
    </MdDialog>
  </template>
</template>
