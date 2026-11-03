<script setup lang="ts">
// Exemptions from the VPN and hosting policy, one page at a time (`?page=`). Moderators and administrators add them in
// the child route `new`; every staff role reads the list. A row opens the exemption's own page.
import { computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute } from 'vue-router'
import { useApi } from '../api/inject'
import type { AllowListItem } from '../api/types'
import DataTable, { type DataColumn } from '../components/DataTable.vue'
import MdButton from '../components/md/MdButton.vue'
import MdDialog from '../components/md/MdDialog.vue'
import PageShell from '../components/PageShell.vue'
import AllowListForm from '../components/intel/AllowListForm.vue'
import ResourceState from '../components/ResourceState.vue'
import StatusPill from '../components/StatusPill.vue'
import { useResource } from '../composables/useResource'
import { formatDateTime, shortHash } from '../format'
import { useQueryPage } from '../router/queryParam'
import { useRoutedDialog } from '../router/routedDialog'
import { useSessionStore } from '../stores/session'

const PAGE_SIZE = 50

const { t, locale } = useI18n()
const api = useApi()
const session = useSessionStore()
const route = useRoute()

const page = useQueryPage()
const list = useResource(() => api.intelAllowList(page.value, PAGE_SIZE))
watch(page, () => void list.reload())
const create = useRoutedDialog({ parent: 'intel-allow-list', child: 'intel-allow-list-new' })

const columns = computed<DataColumn<AllowListItem>[]>(() => [
  { key: 'owner', label: t('intel.allowList.columns.player'), priority: 1 },
  { key: 'reason', label: t('intel.allowList.columns.reason'), priority: 1 },
  { key: 'expiresAt', label: t('intel.allowList.columns.expires'), priority: 2 },
  { key: 'createdAt', label: t('intel.allowList.columns.added'), priority: 2 },
])

function expiryLabel(entry: AllowListItem): string {
  if (entry.expiresAt === null || entry.expiresAt === undefined) return t('intel.allowList.permanentLabel')
  if (entry.expired) return t('intel.allowList.expired')
  return t('intel.allowList.until', { when: formatDateTime(entry.expiresAt, locale.value) })
}

async function added(): Promise<void> {
  await create.close()
  await list.reload()
}

/** The address of another page of the same list, keeping the other query parameters. */
function pageTo(target: number) {
  return { name: 'intel-allow-list', query: { ...route.query, page: target > 0 ? String(target) : undefined } }
}
</script>

<template>
  <PageShell :title="t('intel.allowList.title')" :description="t('intel.allowList.intro')">
    <template #actions>
      <MdButton v-if="session.moderator" as="link" data-testid="new-allow-entry" :to="{ name: 'intel-allow-list-new', query: route.query }">
        {{ t('intel.allowList.new') }}
      </MdButton>
    </template>

    <p v-if="!session.moderator" data-testid="allow-list-readonly" class="mb-4 rounded-md-md bg-surface-container-high p-3 text-on-surface">
      {{ t('intel.allowList.readonly') }}
    </p>

    <ResourceState :loading="list.loading.value" :error="list.error.value" :has-data="list.data.value !== null" @retry="list.reload">
      <template v-if="list.data.value">
        <DataTable v-if="list.data.value.entries.length > 0" :caption="t('intel.allowList.title')" :columns="columns" :rows="list.data.value.entries">
          <template #cell="{ row, column }">
            <RouterLink
              v-if="column.key === 'owner'"
              :to="{ name: 'intel-allow-entry', params: { owner: row.owner } }"
              class="font-mono text-body-medium text-primary underline underline-offset-2 focus-ring"
              :title="row.owner"
            >
              {{ shortHash(row.owner) }}
            </RouterLink>
            <span v-else-if="column.key === 'reason'" class="break-words">{{ row.reason }}</span>
            <StatusPill v-else-if="column.key === 'expiresAt'" :label="expiryLabel(row)" :tone="row.expired ? 'error' : 'neutral'" />
            <template v-else-if="column.key === 'createdAt'">{{ formatDateTime(row.createdAt, locale) }}</template>
          </template>
        </DataTable>
        <p v-else data-testid="allow-list-empty" class="text-body-large text-on-surface-variant">{{ t('intel.allowList.empty') }}</p>

        <nav :aria-label="t('intel.allowList.pages')" class="mt-4 flex items-center gap-3">
          <MdButton v-if="page > 0" as="link" variant="text" data-testid="allow-list-prev" :to="pageTo(page - 1)">{{ t('intel.allowList.previous') }}</MdButton>
          <MdButton v-if="list.data.value.hasMore" as="link" variant="text" data-testid="allow-list-next" :to="pageTo(page + 1)">{{ t('intel.allowList.next') }}</MdButton>
        </nav>
      </template>
    </ResourceState>

    <MdDialog v-if="session.moderator" :open="create.open.value" :headline="t('intel.allowList.new')" wide @close="create.close">
      <AllowListForm :administrator="session.administrator" :entries="[]" @added="added" />
    </MdDialog>
  </PageShell>
</template>
