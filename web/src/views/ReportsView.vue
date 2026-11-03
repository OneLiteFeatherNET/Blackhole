<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { LIST_QUERY } from '../router/listQuery'
import { useListState } from '../router/useListState'
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
import { createListKeys, detailIsShown, listIsShown, rowDomId } from '../nav/listDetail'
import { useBreakpoint } from '../nav/useBreakpoint'
import { useSessionStore } from '../stores/session'
import ReportDetailView from './ReportDetailView.vue'

const OPEN: ReportStatus[] = ['OPEN', 'UNDER_REVIEW']

// The open report is the `id` of the route; this page is the list and, beside or in place of it, the report.
const props = defineProps<{ id?: string }>()

const { t, locale } = useI18n()
const api = useApi()
const session = useSessionStore()
const route = useRoute()
const router = useRouter()
const size = useBreakpoint()
const listShown = computed(() => listIsShown(size.value, props.id !== undefined))
const detailShown = computed(() => detailIsShown(size.value, props.id !== undefined))
const detailPane = ref<HTMLElement | null>(null)

// The one filter is open or all, in the address (`status`). Open is the default, so the address normally has no status.
const LIST = LIST_QUERY.reports!
const values = useListState(LIST)
const showAll = computed(() => values.value.status === 'all')

const filters = computed<FilterDef[]>(() => [
  {
    kind: 'toggle-group',
    id: 'status',
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

// The keys move a cursor over the visible rows. The cursor follows the open report, and is kept on the list.
const cursor = ref(0)
const rowId = (row: Report) => rowDomId('report-row', row.identifier ?? '')

function focusRow(row: Report | undefined): void {
  if (row) void nextTick(() => document.getElementById(rowId(row))?.focus())
}

function syncCursor(): void {
  const index = visible.value.findIndex((row) => row.identifier === props.id)
  cursor.value = index >= 0 ? index : Math.min(cursor.value, Math.max(visible.value.length - 1, 0))
}
watch(() => props.id, syncCursor)
watch(visible, syncCursor)

function moveCursor(step: number): void {
  const rows = visible.value
  if (rows.length === 0) return
  cursor.value = Math.min(Math.max(cursor.value + step, 0), rows.length - 1)
  focusRow(rows[cursor.value])
}

/** Enter opens the row under the cursor, with the list query kept. Below 840dp the focus goes to the report. */
async function openCursorRow(): Promise<void> {
  const row = visible.value[cursor.value]
  if (!row?.identifier) return
  await router.push({ name: 'report', params: { id: row.identifier }, query: route.query })
  if (size.value !== 'expanded') {
    await nextTick()
    detailPane.value?.focus()
  }
}

/** Esc closes the report and puts the focus back on its row. */
async function closeOpenReport(): Promise<void> {
  const previous = visible.value.find((row) => row.identifier === props.id)
  await router.push({ name: 'reports', query: route.query })
  focusRow(previous)
}

const keys = createListKeys({
  listShown: () => listShown.value,
  detailOpen: () => props.id !== undefined,
  next: () => moveCursor(1),
  previous: () => moveCursor(-1),
  open: () => void openCursorRow(),
  close: () => void closeOpenReport(),
})
onMounted(() => document.addEventListener('keydown', keys))
onBeforeUnmount(() => document.removeEventListener('keydown', keys))

/** The row of the open report is marked with aria-current; the cursor row is the one in the tab order. */
function rowProps(row: Report, index: number): Record<string, unknown> {
  const open = row.identifier !== undefined && row.identifier === props.id
  return {
    id: rowId(row),
    tabindex: index === cursor.value ? 0 : -1,
    'aria-current': open ? 'true' : undefined,
    class: open ? 'bg-secondary-container focus-ring' : 'focus-ring',
  }
}

function showEverything(): void {
  values.value = { status: 'all' }
}

async function done(text: string): Promise<void> {
  resolving.value = null
  message.value = text
  await reports.reload()
}
</script>

<template>
  <div class="flex min-w-0 flex-col gap-4 min-[840px]:flex-row min-[840px]:items-start">
    <!-- The list stays mounted while a report is open, so it is not requested again and keeps its scroll and selection. -->
    <div
      v-show="listShown"
      data-testid="list-pane"
      class="flex min-w-0 flex-col min-[840px]:w-96 min-[840px]:shrink-0 min-[840px]:max-h-[calc(100dvh-8rem)] min-[840px]:overflow-y-auto"
    >
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
        <DataTable v-if="visible.length > 0" :caption="t('reports.title')" :columns="columns" :rows="visible" :row-props="rowProps">
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
              <MdButton
                v-if="row.identifier"
                as="link"
                variant="tonal"
                :to="{ name: 'report', params: { id: row.identifier }, query: route.query }"
                :data-testid="`details-${row.identifier}`"
              >
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
    </div>

    <!-- Beside the list from 840dp (a placeholder while nothing is open); in place of the list below it. -->
    <div
      v-if="detailShown"
      ref="detailPane"
      tabindex="-1"
      data-testid="detail-pane"
      class="flex min-w-0 flex-1 flex-col focus:outline-none min-[840px]:max-h-[calc(100dvh-8rem)] min-[840px]:overflow-y-auto"
    >
      <ReportDetailView v-if="props.id !== undefined" :key="props.id" :id="props.id" />
      <p v-else data-testid="detail-placeholder" class="text-body-large text-on-surface-variant">{{ t('reports.selectHint') }}</p>
    </div>
  </div>

  <ReportResolveDialog :open="resolving !== null" :report="resolving" @close="resolving = null" @done="done" />
  <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
</template>
