<script setup lang="ts">
// Disabling a section is a route (`/admin/sections/:key/disable`) that only shows the confirmation: nothing is sent
// on open, back or reload. The section carries no version to send, so the confirmation is the guard.
import { inject, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { useApi } from '../../api/inject'
import { useRoutedDialog } from '../../router/routedDialog'
import { CONFIG_PAGE, NO_CONFIG_PAGE } from '../config/configPage'
import RoutedDialog from '../RoutedDialog.vue'
import MdButton from '../md/MdButton.vue'

const { t } = useI18n()
const api = useApi()
const route = useRoute()
const page = inject(CONFIG_PAGE, NO_CONFIG_PAGE)
const dialog = useRoutedDialog({ parent: 'admin-section', child: 'admin-section-disable' })

const key = String(route.params.key)
const busy = ref(false)
const error = ref('')

async function confirm(): Promise<void> {
  if (busy.value) return
  busy.value = true
  const result = await api.disableSection(key)
  busy.value = false
  if (!result.ok) {
    error.value = result.error.message
    return
  }
  await page.changed(t('sections.disabled'))
  await dialog.close()
}
</script>

<template>
  <RoutedDialog parent="admin-section" child="admin-section-disable" :headline="t('sections.disable.title', { key })">
    <p>{{ t('sections.disable.body') }}</p>
    <p v-if="error" data-testid="error" role="alert" class="mt-3 rounded-md-md bg-error-container p-3 text-on-error-container">{{ error }}</p>
    <template #actions>
      <MdButton variant="error" data-testid="confirm" :disabled="busy" @click="confirm">{{ t('sections.disable.confirm') }}</MdButton>
    </template>
  </RoutedDialog>
</template>
