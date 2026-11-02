<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { type LocationQuery, useRoute, useRouter } from 'vue-router'
import { useApi } from '../api/inject'
import type { CaseListItem, CaseStatus } from '../api/types'
import CaseListCell from '../components/case/CaseListCell.vue'
import CaseBulkDialog from '../components/case/CaseBulkDialog.vue'
import CasePlayerGroups from '../components/case/CasePlayerGroups.vue'
import CaseSelectionBar from '../components/case/CaseSelectionBar.vue'
import DataTable, { type DataColumn } from '../components/DataTable.vue'
import EmptyState from '../components/EmptyState.vue'
import FilterToolbar, { type FilterDef } from '../components/FilterToolbar.vue'
import PageShell from '../components/PageShell.vue'
import ResourceState from '../components/ResourceState.vue'
import MdButton from '../components/md/MdButton.vue'
import MdCheckbox from '../components/md/MdCheckbox.vue'
import MdSegmentedButton, { type SegmentOption } from '../components/md/MdSegmentedButton.vue'
import { useCaseSelection } from '../composables/useCaseSelection'
import { useResource } from '../composables/useResource'
import { useSessionStore } from '../stores/session'
import { type CasesViewMode, readCasesView, writeCasesView } from './casesViewMemory'

const STATUSES: CaseStatus[] = ['OPEN', 'IN_REVIEW', 'WAITING', 'CLOSED']
const TAG = /^[a-z0-9-]{1,32}$/
const FILTER_KEYS = ['status', 'mine', 'tag'] as const

const { t } = useI18n()
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
// The selection belongs to the list on screen: a change of view ends it, like a change of filter or page.
const selection = useCaseSelection()
const bulkOpen = ref(false)
// The grouped view reloads its groups and open players when this moves.
const groupsRevision = ref(0)

function reloadList(): void {
  if (view.value === 'cases') void cases.reload()
  else groupsRevision.value++
}

// A finished batch ends the selection it was made from, and the list shows the new state of the cases.
function onBulkDone(): void {
  selection.clear()
  reloadList()
}

watch(view, (mode) => {
  writeCasesView(mode)
  selection.clear()
  // The flat list is requested only while it is shown, so switching back to it loads it once.
  if (mode === 'cases') void cases.reload()
})

const filtersApplied = computed(() => Object.values(values.value).some((value) => value !== ''))

// The header checkbox of the flat list works on the rows of this page only.
const pageItems = computed(() => cases.data.value?.items ?? [])
const pageSelection = computed(() => selection.summary(pageItems.value))

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
  selection.clear()
  if (view.value === 'cases') void cases.reload()
  void router.replace({ query: toQuery(next) })
})
watch(page, () => {
  selection.clear()
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

    <CaseSelectionBar :count="selection.count.value" :at-cap="selection.atCap.value" :cap="selection.cap" @clear="selection.clear()">
      <template #actions>
        <MdButton variant="tonal" data-testid="selection-edit" @click="bulkOpen = true">{{ t('case.selection.edit') }}</MdButton>
      </template>
    </CaseSelectionBar>

    <!-- The page column stretches its children; self-start keeps the switch as wide as its two segments. -->
    <MdSegmentedButton v-model="view" :label="t('case.list.view')" :options="viewOptions" class="mb-3 self-start" />

    <CasePlayerGroups
      v-if="view === 'player'"
      :status="(values.status || undefined) as CaseStatus | undefined"
      :assignee="values.mine ? session.me?.oid : undefined"
      :tag="values.tag || undefined"
      :filters-applied="filtersApplied"
      :columns="columns"
      :selection="selection"
      :revision="groupsRevision"
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
          leading
          class="bg-surface-container-low"
        >
          <template #head-leading>
            <MdCheckbox
              hide-label
              :label="t('case.list.select.page')"
              :model-value="pageSelection.checked"
              :indeterminate="pageSelection.indeterminate"
              :disabled="!pageSelection.checked && selection.atCap.value"
              @update:model-value="(value: boolean) => selection.toggleMany(pageItems, value)"
            />
          </template>
          <template #cell-leading="{ row }">
            <MdCheckbox
              data-testid="case-select"
              hide-label
              :label="t('case.list.select.case', { id: row.shortId })"
              :model-value="selection.isSelected(row.punishmentIdentifier)"
              :disabled="!selection.canSelect(row.punishmentIdentifier)"
              @update:model-value="(value: boolean) => selection.toggle(row, value)"
            />
          </template>
          <template #cell="{ row, column }">
            <CaseListCell :row="row" :column="column" />
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

    <CaseBulkDialog :open="bulkOpen" :cases="selection.items.value" @close="bulkOpen = false" @done="onBulkDone" @reload="reloadList" />
  </PageShell>
</template>
