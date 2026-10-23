<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { StaffLink } from '../../api/types'
import { useResource } from '../../composables/useResource'
import { formatDateTime } from '../../format'
import MdButton from '../md/MdButton.vue'
import MdDataTable from '../md/MdDataTable.vue'
import MdDialog from '../md/MdDialog.vue'
import MdSnackbar from '../md/MdSnackbar.vue'
import ResourceState from '../ResourceState.vue'

const { t, locale } = useI18n()
const api = useApi()

const links = useResource(() => api.staffLinks())
const removing = ref<StaffLink | null>(null)
const removingNow = ref(false)
const error = ref('')
const message = ref('')

const columns = computed(() => [
  { key: 'displayName' as const, label: t('staffLinks.columns.person') },
  { key: 'minecraftUuid' as const, label: t('staffLinks.columns.minecraft') },
  { key: 'linkedAt' as const, label: t('staffLinks.columns.linkedAt') },
  { key: 'oid' as const, label: t('staffLinks.columns.actions') },
])

function ask(link: StaffLink): void {
  removing.value = link
  error.value = ''
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
  <section aria-labelledby="staff-links-title" class="mt-8 flex flex-col gap-3" data-testid="staff-links">
    <h2 id="staff-links-title" class="text-title-large">{{ t('staffLinks.title') }}</h2>
    <p class="text-body-large text-on-surface-variant">{{ t('staffLinks.intro') }}</p>
    <ResourceState :loading="links.loading.value" :error="links.error.value" :has-data="links.data.value !== null" @retry="links.reload">
      <p v-if="links.data.value?.length === 0" data-testid="staff-links-empty">{{ t('staffLinks.empty') }}</p>
      <MdDataTable v-else-if="links.data.value" :label="t('staffLinks.title')" :columns="columns" :rows="links.data.value">
        <template #cell="{ row, column }">
          <template v-if="column.key === 'displayName'">{{ row.displayName || t('staffLinks.unnamed') }}</template>
          <code v-else-if="column.key === 'minecraftUuid'" class="font-mono text-body-small">{{ row.minecraftUuid }}</code>
          <template v-else-if="column.key === 'linkedAt'">{{ formatDateTime(row.linkedAt, locale) }}</template>
          <MdButton v-else variant="text" :data-testid="`unlink-${row.oid}`" @click="ask(row)">{{ t('staffLinks.unlink') }}</MdButton>
        </template>
      </MdDataTable>
    </ResourceState>
  </section>

  <MdDialog :open="removing !== null" :headline="t('staffLinks.unlinkTitle', { name: removing?.displayName || t('staffLinks.unnamed') })" @close="removing = null">
    <p>{{ t('staffLinks.unlinkBody') }}</p>
    <p v-if="error" data-testid="error" role="alert" class="mt-3 rounded-md-md bg-error-container p-3 text-on-error-container">{{ error }}</p>
    <template #actions>
      <MdButton variant="text" data-testid="cancel" @click="removing = null">{{ t('actions.cancel') }}</MdButton>
      <MdButton data-testid="confirm" :disabled="removingNow" @click="confirm">{{ t('staffLinks.unlinkConfirm') }}</MdButton>
    </template>
  </MdDialog>
  <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
</template>
