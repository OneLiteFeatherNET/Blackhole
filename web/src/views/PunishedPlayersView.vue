<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { type LocationQuery, RouterLink, useRoute, useRouter } from 'vue-router'
import { useApi } from '../api/inject'
import type { ActivePunishment, ActivePunishmentQuery, EnforcementKind } from '../api/types'
import DataTable, { type DataColumn } from '../components/DataTable.vue'
import EmptyState from '../components/EmptyState.vue'
import FilterToolbar, { type FilterDef } from '../components/FilterToolbar.vue'
import PageShell from '../components/PageShell.vue'
import ResourceState from '../components/ResourceState.vue'
import StatusPill from '../components/StatusPill.vue'
import KickDialog from '../components/kick/KickDialog.vue'
import MdButton from '../components/md/MdButton.vue'
import RowMenu, { type RowMenuItem } from '../components/RowMenu.vue'
import WarnDialog from '../components/warning/WarnDialog.vue'
import { useResource } from '../composables/useResource'
import { describeDuration } from '../duration'
import { formatDateTime, shortHash } from '../format'
import { useLookupStore } from '../stores/lookup'
import { useSessionStore } from '../stores/session'

const KINDS: EnforcementKind[] = ['BAN', 'MUTE', 'SERVER']
const TERMS = ['permanent', 'temporary', 'soon'] as const
const SOURCES = ['automatic', 'manual'] as const
/** "Expiring soon" means ending within a day. */
const SOON_MILLIS = 24 * 60 * 60 * 1000

// Every sort is one option: the order is named in the select, so there is no separate direction button.
const SORTS = {
  'created-desc': { sort: 'CREATED', direction: 'DESC' },
  'created-asc': { sort: 'CREATED', direction: 'ASC' },
  'expires-desc': { sort: 'EXPIRES', direction: 'DESC' },
  'expires-asc': { sort: 'EXPIRES', direction: 'ASC' },
} as const
type SortValue = keyof typeof SORTS
const SORT_VALUES = Object.keys(SORTS) as SortValue[]

// Every filter value is a string; '' means the filter is not applied. The server filters are the ones that change the
// request; the search runs on the loaded page only and never sends a request.
const API_FILTERS = ['kind', 'section', 'term', 'source', 'mine', 'sort'] as const
const FILTER_KEYS = ['q', ...API_FILTERS] as const

const { t, locale } = useI18n()
const api = useApi()
const session = useSessionStore()
const lookup = useLookupStore()
const route = useRoute()
const router = useRouter()

// The route query holds the same values, so a reload or Back returns to the same filtered list. Anything the query
// cannot hold is ignored.
function fromQuery(query: LocationQuery): Record<string, string> {
  const pick = <T extends string>(value: unknown, allowed: readonly T[]): string =>
    typeof value === 'string' && (allowed as readonly string[]).includes(value) ? value : ''
  return {
    q: typeof query.q === 'string' ? query.q.slice(0, 64) : '',
    kind: pick(query.kind, KINDS),
    section: typeof query.section === 'string' ? query.section : '',
    term: pick(query.term, TERMS),
    source: pick(query.source, SOURCES),
    mine: query.mine === '1' ? 'mine' : '',
    sort: pick(query.sort, SORT_VALUES),
  }
}

function toQuery(filters: Record<string, string>): LocationQuery {
  const query: LocationQuery = { ...route.query }
  for (const key of FILTER_KEYS) delete query[key]
  for (const key of FILTER_KEYS) {
    if (key === 'mine') {
      if (filters.mine) query.mine = '1'
    } else if (filters[key]) {
      query[key] = filters[key]
    }
  }
  return query
}

const values = ref<Record<string, string>>(fromQuery(route.query))
const page = ref(0)

const serverFiltered = computed(() => API_FILTERS.some((key) => values.value[key] !== ''))

function query(): ActivePunishmentQuery {
  const current = values.value
  const sort = SORTS[(current.sort || 'created-desc') as SortValue]
  return {
    kind: (current.kind || undefined) as EnforcementKind | undefined,
    section: current.section || undefined,
    permanent: current.term === 'permanent' ? true : current.term === 'temporary' || current.term === 'soon' ? false : undefined,
    expiresBefore: current.term === 'soon' ? Date.now() + SOON_MILLIS : undefined,
    automatic: current.source === '' ? undefined : current.source === 'automatic',
    actor: current.mine ? session.me?.oid : undefined,
    sort: sort.sort,
    direction: sort.direction,
    page: page.value,
  }
}

const punished = useResource(() => api.activePunishments(query()))
const sections = useResource(() => api.sections())

// A change of a server filter starts again at the first page. A search or the query alone does not send a request.
watch(values, (next, previous) => {
  if (API_FILTERS.some((key) => next[key] !== previous?.[key])) {
    if (page.value === 0) void punished.reload()
    else page.value = 0
  }
  void router.replace({ query: toQuery(next) })
})
watch(page, () => void punished.reload())

// Back and forward change the query; the filters follow it.
watch(
  () => route.query,
  (query) => {
    const next = fromQuery(query)
    if (FILTER_KEYS.some((key) => next[key] !== values.value[key])) values.value = next
  },
)

function clearFilters(): void {
  values.value = Object.fromEntries(FILTER_KEYS.map((key) => [key, '']))
}

function clearSearch(): void {
  values.value = { ...values.value, q: '' }
}

const sectionOptions = computed(() => [
  { value: '', label: t('punished.allSections') },
  ...(sections.data.value ?? []).map((entry) => ({ value: entry.key, label: entry.displayName })),
])

const filters = computed<FilterDef[]>(() => [
  { kind: 'search', id: 'q', label: t('punished.search.label') },
  { kind: 'select', id: 'section', label: t('punished.section'), options: sectionOptions.value },
  {
    kind: 'select',
    id: 'term',
    label: t('punished.term.label'),
    options: [{ value: '', label: t('punished.term.all') }, ...TERMS.map((value) => ({ value, label: t(`punished.term.${value}`) }))],
  },
  {
    kind: 'select',
    id: 'sort',
    label: t('punished.sort.label'),
    options: SORT_VALUES.map((value) => ({ value: value === 'created-desc' ? '' : value, label: t(`punished.sort.options.${value}`) })),
  },
  {
    kind: 'toggle-group',
    id: 'kind',
    label: t('punished.kind.label'),
    options: KINDS.map((value) => ({ value, label: t(`punished.kind.${value}`), testid: `kind-${value}` })),
  },
  {
    kind: 'toggle-group',
    id: 'source',
    label: t('punished.source.label'),
    options: SOURCES.map((value) => ({ value, label: t(`punished.source.${value}`), testid: `source-${value}` })),
  },
  { kind: 'toggle-group', id: 'mine', label: t('punished.mine'), options: [{ value: 'mine', label: t('punished.mine'), testid: 'filter-mine' }] },
])

/**
 * Names are not stored (the backend only knows hashed owners), so the search matches the names this
 * session has resolved, the start of the owner hash, the case id, the section and the reason.
 */
function matches(row: ActivePunishment, needle: string): boolean {
  const haystack = [lookup.nameOf(row.owner), row.owner.slice(0, 16), row.shortId.replace('#', ''), row.shortId, row.sectionName, row.section, row.reason]
  return haystack.some((value) => value?.toLowerCase().includes(needle))
}

// The search only narrows the rows of the loaded page; the server does not search yet.
const visible = computed(() => {
  const needle = values.value.q.trim().toLowerCase()
  const rows = punished.data.value?.items ?? []
  return needle ? rows.filter((row) => matches(row, needle)) : rows
})

const columns = computed<DataColumn<ActivePunishment>[]>(() => [
  { key: 'owner', label: t('punished.columns.player'), priority: 1 },
  { key: 'section', label: t('punished.columns.type'), priority: 1 },
  { key: 'reason', label: t('punished.columns.reason'), priority: 2 },
  { key: 'createdAt', label: t('punished.columns.since'), priority: 3 },
  { key: 'expiresAt', label: t('punished.columns.until'), priority: 2 },
  { key: 'automatic', label: t('punished.columns.source'), priority: 3 },
  { key: 'actorName', label: t('punished.columns.issuer'), priority: 3 },
  { key: 'caseStatus', label: t('punished.columns.case'), priority: 1 },
  // The row actions (the overflow menu) have a column of their own for staff who may act; `owner` is only a key here.
  ...(session.moderator ? [{ key: 'punishmentId' as const, label: t('punished.columns.actions'), priority: 1 as const, align: 'end' as const }] : []),
])

const rowMenu = computed<RowMenuItem[]>(() => [
  { key: 'warn', label: t('warn.open') },
  { key: 'kick', label: t('kick.open') },
])
const kickTarget = ref<ActivePunishment | null>(null)
const warnTarget = ref<ActivePunishment | null>(null)

function onRowMenu(row: ActivePunishment, key: string): void {
  if (key === 'kick') kickTarget.value = row
  if (key === 'warn') warnTarget.value = row
}

function playerName(row: ActivePunishment): string {
  return lookup.nameOf(row.owner) ?? shortHash(row.owner)
}

function reasonOf(row: ActivePunishment): string {
  // The stored reason of an automatic temporary punishment is an English placeholder.
  if (row.automatic && !row.permanent) return t('punished.automaticReason')
  return row.reason ?? row.templateKey ?? t('punished.noReason')
}

/** Time left, rounded to hours from a day on and to minutes below that, never less than a minute. */
function remaining(expiresAt: number): string {
  const seconds = Math.max(0, Math.round((expiresAt - Date.now()) / 1000))
  const step = seconds >= 86_400 ? 3_600 : 60
  return t('punished.remaining', { duration: describeDuration(Math.max(60, Math.round(seconds / step) * step), locale.value) })
}
</script>

<template>
  <PageShell :title="t('punished.title')" :description="t('punished.search.hint')">
    <template #toolbar>
      <FilterToolbar v-model="values" :filters="filters" />
    </template>

    <DataTable
      v-if="punished.loading.value && punished.data.value === null"
      :caption="t('punished.title')"
      :columns="columns"
      :rows="[]"
      loading
    />

    <ResourceState :loading="punished.loading.value" :error="punished.error.value" :has-data="punished.data.value !== null" @retry="punished.reload">
      <template v-if="punished.data.value">
        <DataTable v-if="visible.length > 0" :caption="t('punished.title')" :columns="columns" :rows="visible">
          <template #cell="{ row, column }">
            <RouterLink
              v-if="column.key === 'owner'"
              :to="{ name: 'player', params: { owner: row.owner } }"
              class="text-primary underline underline-offset-2 outline-none focus-visible:outline-2 focus-visible:outline-primary"
            >
              {{ playerName(row) }}
            </RouterLink>
            <template v-else-if="column.key === 'section'">
              <span class="block">{{ row.kind ? t(`enforcement.${row.kind}`) : '—' }}</span>
              <span class="block text-on-surface-variant">{{ row.sectionName ?? row.section }}</span>
            </template>
            <template v-else-if="column.key === 'reason'">{{ reasonOf(row) }}</template>
            <template v-else-if="column.key === 'createdAt'">{{ formatDateTime(row.createdAt, locale) }}</template>
            <template v-else-if="column.key === 'expiresAt'">
              <StatusPill v-if="row.permanent || row.expiresAt == null" :label="t('punished.permanent')" tone="error" />
              <template v-else>
                <span class="block">{{ formatDateTime(row.expiresAt, locale) }}</span>
                <span class="block text-on-surface-variant">{{ remaining(row.expiresAt) }}</span>
              </template>
            </template>
            <StatusPill v-else-if="column.key === 'automatic'" :label="row.automatic ? t('punished.automatic') : t('punished.manual')" :tone="row.automatic ? 'tertiary' : 'neutral'" />
            <template v-else-if="column.key === 'actorName'">{{ row.actorName ?? (row.automatic ? '—' : t('punished.unverified')) }}</template>
            <template v-else-if="column.key === 'caseStatus'">
              <div class="flex flex-wrap items-center gap-2">
                <RouterLink
                  :to="{ name: 'case', params: { reference: row.punishmentId } }"
                  class="text-primary underline underline-offset-2 outline-none focus-visible:outline-2 focus-visible:outline-primary"
                >
                  {{ row.shortId }}
                </RouterLink>
                <StatusPill v-if="row.caseStatus" :label="t(`case.status.${row.caseStatus}`)" tone="primary" />
                <span v-else class="text-on-surface-variant">{{ t('punished.noCase') }}</span>
              </div>
            </template>
            <div v-else-if="column.key === 'punishmentId'" class="flex justify-end">
              <RowMenu :label="playerName(row)" :items="rowMenu" @select="(key) => onRowMenu(row, key)" />
            </div>
            <template v-else>{{ row[column.key] }}</template>
          </template>
        </DataTable>

        <EmptyState
          v-else-if="punished.data.value.items.length === 0"
          data-testid="punished-empty"
          :variant="serverFiltered ? 'no-matches' : 'no-items'"
          :title="serverFiltered ? t('punished.emptyFiltered') : t('punished.empty')"
        >
          <MdButton v-if="serverFiltered" variant="text" data-testid="empty-clear-filters" @click="clearFilters">
            {{ t('filters.clearFilters') }}
          </MdButton>
        </EmptyState>

        <EmptyState v-else data-testid="punished-no-match" variant="no-matches" :title="t('punished.emptySearch')">
          <MdButton variant="text" data-testid="empty-clear-search" @click="clearSearch">{{ t('punished.clearSearch') }}</MdButton>
        </EmptyState>

        <nav v-if="punished.data.value.totalPages > 1" class="flex items-center gap-2" :aria-label="t('punished.paging')">
          <MdButton variant="text" :disabled="page === 0" data-testid="prev" @click="page--">{{ t('evidence.paging.previous') }}</MdButton>
          <span class="text-body-medium" aria-live="polite">{{ t('evidence.paging.position', { page: page + 1, total: punished.data.value.totalPages }) }}</span>
          <MdButton variant="text" :disabled="page + 1 >= punished.data.value.totalPages" data-testid="next" @click="page++">{{ t('evidence.paging.next') }}</MdButton>
        </nav>
      </template>
    </ResourceState>

    <KickDialog
      v-if="kickTarget && session.moderator"
      open
      :owner="kickTarget.owner"
      :name="playerName(kickTarget)"
      @close="kickTarget = null"
    />
    <WarnDialog v-if="warnTarget && session.moderator" open :owner="warnTarget.owner" @close="warnTarget = null" />
  </PageShell>
</template>
