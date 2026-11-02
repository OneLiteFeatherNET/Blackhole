<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { type LocationQuery, RouterLink, useRoute, useRouter } from 'vue-router'
import { useApi } from '../api/inject'
import type { CaseListItem, CaseStatus } from '../api/types'
import CasePlayerGroups from '../components/case/CasePlayerGroups.vue'
import { CASE_STATUS_TONE, PRIORITY_TONE, punishmentStateTone } from '../components/case/caseTones'
import DataTable, { type DataColumn } from '../components/DataTable.vue'
import EmptyState from '../components/EmptyState.vue'
import FilterToolbar, { type FilterDef } from '../components/FilterToolbar.vue'
import PageShell from '../components/PageShell.vue'
import ResourceState from '../components/ResourceState.vue'
import StatusPill from '../components/StatusPill.vue'
import MdButton from '../components/md/MdButton.vue'
import MdSegmentedButton, { type SegmentOption } from '../components/md/MdSegmentedButton.vue'
import { useResource } from '../composables/useResource'
import { formatDateTime } from '../format'
import { useSessionStore } from '../stores/session'
import { type CasesViewMode, readCasesView, writeCasesView } from './casesViewMemory'

const STATUSES: CaseStatus[] = ['OPEN', 'IN_REVIEW', 'WAITING', 'CLOSED']
const TAG = /^[a-z0-9-]{1,32}$/
const FILTER_KEYS = ['status', 'mine', 'tag'] as const

const { t, locale } = useI18n()
const api = useApi()
const session = useSessionStore()
const route = useRoute()
const router = useRouter()

// Every filter value is a string; '' means the filter is not applied. The route query holds the same values, so a
// reload or Back returns to the same filtered list. Anything the query cannot hold is ignored.
function fromQuery(query: LocationQuery): Record<string, string> {
  const status = typeof query.status === 'string' && (STATUSES as string[]).includes(query.status) ? query.status : ''
  const tag = typeof query.tag === 'string' && TAG.test(query.tag) ? query.tag : ''
  return { status, mine: query.mine === '1' ? 'mine' : '', tag }
}

function toQuery(filters: Record<string, string>): LocationQuery {
  const query: LocationQuery = { ...route.query }
  for (const key of FILTER_KEYS) delete query[key]
  if (filters.status) query.status = filters.status
  if (filters.mine) query.mine = '1'
  if (filters.tag) query.tag = filters.tag
  return query
}

const values = ref<Record<string, string>>(fromQuery(route.query))
const page = ref(0)
// The chosen view is remembered in this browser only; the filters apply to both views.
const view = ref<CasesViewMode>(readCasesView())
watch(view, (mode) => {
  writeCasesView(mode)
  // The flat list is requested only while it is shown, so switching back to it loads it once.
  if (mode === 'cases') void cases.reload()
})

const filtersApplied = computed(() => Object.values(values.value).some((value) => value !== ''))

const cases = useResource(
  () =>
    api.cases({
      status: (values.value.status || undefined) as CaseStatus | undefined,
      assignee: values.value.mine ? session.me?.oid : undefined,
      tag: values.value.tag || undefined,
      page: page.value,
    }),
  { immediate: view.value === 'cases' },
)

// Any filter starts again at the first page, and the query follows the filters. The flat list reloads only while shown.
watch(values, (next) => {
  page.value = 0
  if (view.value === 'cases') void cases.reload()
  void router.replace({ query: toQuery(next) })
})
watch(page, () => {
  if (view.value === 'cases') void cases.reload()
})

// Back and forward change the query; the filters follow it.
watch(
  () => route.query,
  (query) => {
    const next = fromQuery(query)
    if (FILTER_KEYS.some((key) => next[key] !== values.value[key])) values.value = next
  },
)

function clearFilters(): void {
  values.value = { status: '', mine: '', tag: '' }
}

const filters = computed<FilterDef[]>(() => [
  {
    kind: 'toggle-group',
    id: 'status',
    label: t('case.header.status'),
    options: [{ value: '', label: t('case.list.allStatuses') }, ...STATUSES.map((value) => ({ value, label: t(`case.status.${value}`) }))],
  },
  {
    kind: 'toggle-group',
    id: 'mine',
    label: t('case.list.mine'),
    options: [{ value: 'mine', label: t('case.list.mine'), testid: 'filter-mine' }],
  },
  { kind: 'tag', id: 'tag', label: t('case.list.tag'), pattern: TAG, invalid: t('case.header.tagInvalid') },
])

const viewOptions = computed<SegmentOption<CasesViewMode>[]>(() => [
  { value: 'cases', label: t('case.list.viewCases'), testid: 'view-cases' },
  { value: 'player', label: t('case.list.viewPlayer'), testid: 'view-player' },
])

const columns = computed<DataColumn<CaseListItem>[]>(() => [
  { key: 'shortId', label: t('case.list.columns.case'), priority: 1 },
  { key: 'playerName', label: t('case.list.columns.player'), priority: 2 },
  { key: 'section', label: t('case.list.columns.section'), priority: 3 },
  { key: 'punishmentState', label: t('case.list.columns.punishment'), priority: 2 },
  { key: 'status', label: t('case.list.columns.status'), priority: 1 },
  { key: 'assigneeName', label: t('case.list.columns.assignee'), priority: 2 },
  { key: 'priority', label: t('case.list.columns.priority'), priority: 2 },
  { key: 'tags', label: t('case.list.columns.tags'), priority: 3 },
  { key: 'lastActivityAt', label: t('case.list.columns.activity'), priority: 3 },
])
</script>

<template>
  <PageShell :title="t('case.list.title')">
    <template #toolbar>
      <FilterToolbar v-model="values" :filters="filters" />
    </template>

    <MdSegmentedButton v-model="view" :label="t('case.list.view')" :options="viewOptions" class="mb-3" />

    <CasePlayerGroups
      v-if="view === 'player'"
      :status="(values.status || undefined) as CaseStatus | undefined"
      :assignee="values.mine ? session.me?.oid : undefined"
      :tag="values.tag || undefined"
      :filters-applied="filtersApplied"
      @clear-filters="clearFilters"
    />

    <template v-else>
    <DataTable
      v-if="cases.loading.value && cases.data.value === null"
      :caption="t('case.list.title')"
      :columns="columns"
      :rows="[]"
      class="bg-surface-container-low"
      loading
    />

    <ResourceState :loading="cases.loading.value" :error="cases.error.value" :has-data="cases.data.value !== null" @retry="cases.reload">
      <template v-if="cases.data.value">
        <DataTable
          v-if="cases.data.value.items.length > 0"
          :caption="t('case.list.title')"
          :columns="columns"
          :rows="cases.data.value.items"
          class="bg-surface-container-low"
        >
          <template #cell="{ row, column }">
            <RouterLink
              v-if="column.key === 'shortId'"
              :to="{ name: 'case', params: { reference: row.punishmentIdentifier } }"
              class="text-primary underline underline-offset-2 outline-none focus-visible:outline-2 focus-visible:outline-primary"
            >
              {{ row.shortId }}
            </RouterLink>
            <template v-else-if="column.key === 'playerName'">{{ row.playerName ?? t('case.list.unknownPlayer', { hash: row.ownerShort }) }}</template>
            <StatusPill
              v-else-if="column.key === 'punishmentState'"
              :label="t(`case.state.${row.punishmentState}`)"
              :tone="punishmentStateTone(row.punishmentState)"
            />
            <StatusPill v-else-if="column.key === 'status'" :label="t(`case.status.${row.status}`)" :tone="CASE_STATUS_TONE[row.status]" />
            <template v-else-if="column.key === 'assigneeName'">{{ row.assigneeName ?? (row.assigneeOid ? `${row.assigneeOid.slice(0, 8)}…` : t('case.header.unassigned')) }}</template>
            <StatusPill v-else-if="column.key === 'priority'" :label="t(`case.priority.${row.priority}`)" :tone="PRIORITY_TONE[row.priority]" />
            <ul v-else-if="column.key === 'tags'" class="flex flex-wrap gap-1" :aria-label="t('case.header.tags')">
              <li
                v-for="tag in row.tags"
                :key="tag"
                class="inline-flex h-6 items-center rounded-md-sm border border-outline px-2 text-label-medium whitespace-nowrap text-on-surface-variant"
              >
                {{ tag }}
              </li>
            </ul>
            <template v-else-if="column.key === 'lastActivityAt'">{{ formatDateTime(row.lastActivityAt, locale) }}</template>
            <template v-else>{{ row[column.key] }}</template>
          </template>
        </DataTable>

        <EmptyState
          v-else
          data-testid="cases-empty"
          :variant="filtersApplied ? 'no-matches' : 'no-items'"
          :title="filtersApplied ? t('case.list.empty') : t('case.list.noneYet')"
        >
          <MdButton v-if="filtersApplied" variant="text" data-testid="empty-clear-filters" @click="clearFilters">
            {{ t('filters.clearFilters') }}
          </MdButton>
        </EmptyState>

        <nav v-if="cases.data.value.totalPages > 1" class="flex items-center gap-2" :aria-label="t('case.list.paging')">
          <MdButton variant="text" :disabled="page === 0" data-testid="prev" @click="page--">{{ t('evidence.paging.previous') }}</MdButton>
          <span class="text-body-medium" aria-live="polite">{{ t('evidence.paging.position', { page: page + 1, total: cases.data.value.totalPages }) }}</span>
          <MdButton variant="text" :disabled="page + 1 >= cases.data.value.totalPages" data-testid="next" @click="page++">{{ t('evidence.paging.next') }}</MdButton>
        </nav>
      </template>
    </ResourceState>
    </template>
  </PageShell>
</template>
