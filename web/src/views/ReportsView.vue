<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { type LocationQuery, useRoute, useRouter } from 'vue-router'
import { useApi } from '../api/inject'
import type { Report, ReportStatus } from '../api/types'
import DataTable, { type DataColumn } from '../components/DataTable.vue'
import EmptyState from '../components/EmptyState.vue'
import FilterToolbar, { type FilterDef } from '../components/FilterToolbar.vue'
import PageShell from '../components/PageShell.vue'
import ResourceState from '../components/ResourceState.vue'
import RowMenu from '../components/RowMenu.vue'
import StatusPill from '../components/StatusPill.vue'
import TextLink from '../components/TextLink.vue'
import MdButton from '../components/md/MdButton.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import ReportResolveDialog from '../components/reports/ReportResolveDialog.vue'
import { useResource } from '../composables/useResource'
import { formatDateTime, shortHash } from '../format'
import { useSessionStore } from '../stores/session'

const OPEN: ReportStatus[] = ['OPEN', 'UNDER_REVIEW']

const { t, locale } = useI18n()
const api = useApi()
const session = useSessionStore()
const route = useRoute()
const router = useRouter()

// The one filter is open or all. Open is the default, so the route query only carries `show=all`.
function fromQuery(query: LocationQuery): Record<string, string> {
  return { show: query.show === 'all' ? 'all' : '' }
}

function toQuery(filters: Record<string, string>): LocationQuery {
  const query: LocationQuery = { ...route.query }
  delete query.show
  if (filters.show) query.show = filters.show
  return query
}

const values = ref<Record<string, string>>(fromQuery(route.query))
const showAll = computed(() => values.value.show === 'all')

const filters = computed<FilterDef[]>(() => [
  {
    kind: 'toggle-group',
    id: 'show',
    label: t('reports.filter'),
    options: [
      { value: '', label: t('reports.filterOpen'), testid: 'filter-open' },
      { value: 'all', label: t('reports.filterAll'), testid: 'filter-all' },
    ],
  },
])

const reports = useResource(() => api.reports())
const resolving = ref<Report | null>(null)
const message = ref('')

const visible = computed(() => (reports.data.value ?? []).filter((entry) => showAll.value || OPEN.includes(entry.status ?? 'OPEN')))
const isOpen = (entry: Report) => OPEN.includes(entry.status ?? 'OPEN')

const columns = computed<DataColumn<Report>[]>(() => [
  { key: 'createdAt', label: t('reports.columns.when'), priority: 2 },
  { key: 'category', label: t('reports.columns.category'), priority: 2 },
  { key: 'reportedHash', label: t('reports.columns.reported'), priority: 1 },
  { key: 'reporterHash', label: t('reports.columns.reporter'), priority: 3 },
  { key: 'description', label: t('reports.columns.description'), priority: 1 },
  { key: 'status', label: t('reports.columns.status'), priority: 1 },
  { key: 'identifier', label: t('reports.columns.actions'), priority: 1, align: 'end' },
])

// The filter writes the route query; the route query (Back, a reload) writes the filter.
watch(values, (next) => void router.replace({ query: toQuery(next) }))
watch(
  () => route.query,
  (query) => {
    const next = fromQuery(query)
    if (next.show !== values.value.show) values.value = next
  },
)

function showEverything(): void {
  values.value = { show: 'all' }
}

async function done(text: string): Promise<void> {
  resolving.value = null
  message.value = text
  await reports.reload()
}
</script>

<template>
  <PageShell :title="t('reports.title')">
    <template #toolbar>
      <FilterToolbar v-model="values" :filters="filters" />
    </template>

    <DataTable
      v-if="reports.loading.value && reports.data.value === null"
      :caption="t('reports.title')"
      :columns="columns"
      :rows="[]"
      loading
    />

    <ResourceState :loading="reports.loading.value" :error="reports.error.value" :has-data="reports.data.value !== null" @retry="reports.reload">
      <template v-if="reports.data.value">
        <DataTable v-if="visible.length > 0" :caption="t('reports.title')" :columns="columns" :rows="visible">
          <template #cell="{ row, column }">
            <template v-if="column.key === 'createdAt'">{{ formatDateTime(row.createdAt, locale) }}</template>
            <template v-else-if="column.key === 'category'">{{ t(`reports.category.${row.category}`) }}</template>
            <TextLink v-else-if="column.key === 'reportedHash'" :to="`/players/${row.reportedHash}`" :title="row.reportedHash">
              {{ shortHash(row.reportedHash) }}
            </TextLink>
            <span v-else-if="column.key === 'reporterHash'" :title="row.reporterHash">{{ shortHash(row.reporterHash) }}</span>
            <p v-else-if="column.key === 'description'" class="max-w-xs break-words">{{ row.description }}</p>
            <StatusPill
              v-else-if="column.key === 'status'"
              :label="t(`reports.status.${row.status ?? 'OPEN'}`)"
              :tone="isOpen(row) ? 'tertiary' : 'neutral'"
            />
            <!-- One primary action: the details. Resolving is a secondary action, so it sits in the row menu. -->
            <div v-else-if="column.key === 'identifier'" class="flex items-center justify-end gap-2">
              <MdButton v-if="row.identifier" as="link" variant="tonal" :to="`/reports/${row.identifier}`" :data-testid="`details-${row.identifier}`">
                {{ t('reports.detail.open') }}
              </MdButton>
              <RowMenu
                v-if="session.moderator && isOpen(row) && row.identifier"
                :label="t(`reports.category.${row.category}`)"
                :items="[{ key: 'resolve', label: t('reports.resolve.open') }]"
                @select="resolving = row"
              />
            </div>
            <template v-else>{{ row[column.key] }}</template>
          </template>
        </DataTable>

        <EmptyState
          v-else
          data-testid="reports-empty"
          :variant="showAll ? 'no-items' : 'no-matches'"
          :title="showAll ? t('reports.noneYet') : t('reports.empty')"
        >
          <MdButton v-if="!showAll" variant="text" data-testid="empty-show-all" @click="showEverything">
            {{ t('reports.showAll') }}
          </MdButton>
        </EmptyState>
      </template>
    </ResourceState>
  </PageShell>

  <ReportResolveDialog :open="resolving !== null" :report="resolving" @close="resolving = null" @done="done" />
  <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
</template>
