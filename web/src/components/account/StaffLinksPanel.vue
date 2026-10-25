<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { StaffLink } from '../../api/types'
import { useResource } from '../../composables/useResource'
import { formatDateTime } from '../../format'
import DataTable, { type DataColumn } from '../DataTable.vue'
import MdButton from '../md/MdButton.vue'
import MdDialog from '../md/MdDialog.vue'
import MdSnackbar from '../md/MdSnackbar.vue'
import ResourceState from '../ResourceState.vue'
import RowMenu, { type RowMenuItem } from '../RowMenu.vue'

const { t, locale } = useI18n()
const api = useApi()

const links = useResource(() => api.staffLinks())
const removing = ref<StaffLink | null>(null)
const removingNow = ref(false)
const error = ref('')
const message = ref('')

const columns = computed<DataColumn<StaffLink>[]>(() => [
  { key: 'displayName', label: t('staffLinks.columns.person'), priority: 1 },
  { key: 'minecraftUuid', label: t('staffLinks.columns.minecraft'), priority: 2 },
  { key: 'linkedAt', label: t('staffLinks.columns.linkedAt'), priority: 3 },
  { key: 'oid', label: t('staffLinks.columns.actions'), priority: 1, align: 'end' },
])

// Removing a link is destructive, so it lives in the row menu and is confirmed before anything is sent.
const rowMenu = computed<RowMenuItem[]>(() => [{ key: 'unlink', label: t('staffLinks.unlink'), destructive: true }])

function ask(link: StaffLink): void {
  removing.value = link
  error.value = ''
}

function onRowMenu(link: StaffLink, key: string): void {
  if (key === 'unlink') ask(link)
}

async function confirm(): Promise<void> {
  if (!removing.value || removingNow.value) return
  removingNow.value = true
  const result = await api.unlinkStaffLink(removing.value.oid)
  removingNow.value = false
  if (!result.ok) {
    error.value = result.error.message
    return
  }
  removing.value = null
  message.value = t('staffLinks.removed')
  await links.reload()
}
</script>

<template>
  <section aria-labelledby="staff-links-title" class="flex flex-col gap-3" data-testid="staff-links">
    <h2 id="staff-links-title" class="text-title-medium">{{ t('staffLinks.title') }}</h2>
    <p class="text-body-large text-on-surface-variant">{{ t('staffLinks.intro') }}</p>
    <ResourceState :loading="links.loading.value" :error="links.error.value" :has-data="links.data.value !== null" @retry="links.reload">
      <p v-if="links.data.value?.length === 0" data-testid="staff-links-empty">{{ t('staffLinks.empty') }}</p>
      <DataTable v-else-if="links.data.value" :caption="t('staffLinks.title')" :columns="columns" :rows="links.data.value">
        <template #cell="{ row, column }">
          <template v-if="column.key === 'displayName'">{{ row.displayName || t('staffLinks.unnamed') }}</template>
          <code v-else-if="column.key === 'minecraftUuid'" class="font-mono text-body-small">{{ row.minecraftUuid }}</code>
          <template v-else-if="column.key === 'linkedAt'">{{ formatDateTime(row.linkedAt, locale) }}</template>
          <div v-else class="flex justify-end">
            <RowMenu :label="row.displayName || t('staffLinks.unnamed')" :items="rowMenu" @select="(key) => onRowMenu(row, key)" />
          </div>
        </template>
      </DataTable>
    </ResourceState>
  </section>

  <MdDialog :open="removing !== null" :headline="t('staffLinks.unlinkTitle', { name: removing?.displayName || t('staffLinks.unnamed') })" @close="removing = null">
    <p>{{ t('staffLinks.unlinkBody') }}</p>
    <p v-if="error" data-testid="error" role="alert" class="mt-3 rounded-md-md bg-error-container p-3 text-on-error-container">{{ error }}</p>
    <template #actions>
      <MdButton variant="text" data-testid="cancel" @click="removing = null">{{ t('actions.cancel') }}</MdButton>
      <MdButton variant="error" data-testid="confirm" :disabled="removingNow" @click="confirm">{{ t('staffLinks.unlinkConfirm') }}</MdButton>
    </template>
  </MdDialog>
  <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
</template>
