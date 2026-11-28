<script setup lang="ts">
// The staff audit log (ADMIN only, see AdminView). Read only: newest first, one cursor page at a time. The filters, the
// cursor and the open entry are in the address (`/admin/audit?action=&actor=&target=&cursor=`, `/admin/audit/:auditId`),
// so a link shows the same view. An entry opens in a side sheet, loaded by its id, with a link to its case or player page.
// The backend is authoritative for access; this screen is only offered to administrators.
import { computed, onMounted, ref, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute, useRouter, type LocationQuery } from 'vue-router'
import { useApi } from '../../api/inject'
import type { ApiError, AuditEntry, AuditQuery } from '../../api/types'
import { formatDateTime, shortHash } from '../../format'
import CopyLink from '../CopyLink.vue'
import DataTable, { type DataColumn } from '../DataTable.vue'
import DefinitionList from '../DefinitionList.vue'
import EmptyState from '../EmptyState.vue'
import FilterToolbar, { type FilterDef } from '../FilterToolbar.vue'
import ResourceState from '../ResourceState.vue'
import MdButton from '../md/MdButton.vue'
import SidePanel from '../panel/SidePanel.vue'
import { useWindowClass } from '../panel/useWindowClass'

const PAGE_SIZE = 50
// The wire names the backend writes (see its AuditAction); the labels are translated below.
const ACTION_KEYS: Record<string, string> = {
  'kick.create': 'kickCreate',
  'kick.mass': 'kickMass',
  'punishment.revoke': 'punishmentRevoke',
  'staff-link.unlink': 'staffLinkUnlink',
}
// A link is only drawn to a path the panel really has; anything else in a payload is shown as text.
const PANEL_PATH = /^\/(cases|players)\/[A-Za-z0-9._-]{1,128}$/
const FILTER_KEYS = ['action', 'actor', 'target'] as const

const { t, locale } = useI18n()
const api = useApi()
const route = useRoute()
const router = useRouter()
const windowClass = useWindowClass()

function queryText(key: string): string {
  const value = route.query[key]
  return typeof value === 'string' ? value : ''
}

/** The filters of the address. Changing one starts again at the first page (the cursor is dropped). */
const values = computed<Record<string, string>>({
  get: () => Object.fromEntries(FILTER_KEYS.map((key) => [key, queryText(key)])),
  set: (next) => {
    const query: LocationQuery = { ...route.query }
    for (const key of FILTER_KEYS) {
      if (next[key]) query[key] = next[key]
      else delete query[key]
    }
    delete query.cursor
    void router.replace({ query })
  },
})
const cursor = computed(() => queryText('cursor') || undefined)

const rows = shallowRef<AuditEntry[]>([])
const nextCursor = ref<string | null>(null)
const shown = ref(false)
const loading = ref(true)
const error = ref<ApiError | null>(null)

const entryId = computed(() => (typeof route.params.auditId === 'string' ? route.params.auditId : null))
const entry = ref<AuditEntry | null>(null)
const entryState = ref<'loading' | 'found' | 'missing' | 'failed'>('loading')

const filtersApplied = computed(() => FILTER_KEYS.some((key) => queryText(key) !== ''))

// The newest answer wins: a filter changed while a page is on its way must not be overwritten by the old page.
let generation = 0

function queryOf(): AuditQuery {
  const query: AuditQuery = { limit: PAGE_SIZE }
  if (cursor.value) query.cursor = cursor.value
  if (queryText('action')) query.action = queryText('action')
  if (queryText('actor').trim()) query.actorOid = queryText('actor').trim()
  if (queryText('target').trim()) query.targetRef = queryText('target').trim()
  return query
}

/** One page: the first one, or the one the address names by its cursor. */
async function load(): Promise<void> {
  const current = ++generation
  loading.value = true
  const result = await api.auditLog(queryOf())
  if (current !== generation) return
  loading.value = false
  if (!result.ok) {
    error.value = result.error
    rows.value = []
    shown.value = false
    return
  }
  error.value = null
  rows.value = result.data.items
  nextCursor.value = result.data.nextCursor ?? null
  shown.value = true
}

async function loadEntry(id: string | null): Promise<void> {
  if (id === null) {
    entry.value = null
    return
  }
  entryState.value = 'loading'
  const result = await api.auditEntry(id)
  if (id !== entryId.value) return
  if (result.ok) {
    entry.value = result.data
    entryState.value = 'found'
    return
  }
  entry.value = null
  entryState.value = result.error.kind === 'NOT_FOUND' ? 'missing' : 'failed'
}

onMounted(() => void load())
watch(() => JSON.stringify([queryText('action'), queryText('actor'), queryText('target'), cursor.value ?? '']), () => void load())
watch(entryId, (id) => void loadEntry(id), { immediate: true })

function clearFilters(): void {
  values.value = {}
}

/** The next page is a new address, so Back returns to the page before it. */
function nextPage(): void {
  if (!nextCursor.value) return
  void router.push({ query: { ...route.query, cursor: nextCursor.value } })
}

function firstPage(): void {
  const query: LocationQuery = { ...route.query }
  delete query.cursor
  void router.push({ query })
}

function openEntry(row: AuditEntry): void {
  void router.push({ name: 'admin-audit-entry', params: { auditId: row.auditId }, query: route.query })
}

function closeEntry(): void {
  void router.push({ name: 'admin-audit', query: route.query })
}

function actionLabel(action: string): string {
  const key = ACTION_KEYS[action]
  return key ? t(`audit.actions.${key}`) : action
}

function linkOf(item: AuditEntry): string | null {
  return item.panelPath && PANEL_PATH.test(item.panelPath) ? item.panelPath : null
}

function targetText(item: AuditEntry): string {
  const kind = t(`audit.targets.${item.targetKind}`)
  if (!item.targetRef) return kind
  // A player is named by its owner hash, shortened like everywhere else; a case by its short id.
  const ref = item.targetKind === 'PLAYER' ? shortHash(item.targetRef) : item.targetRef
  return `${kind} ${ref}`
}

function whoText(item: AuditEntry): string {
  return item.actorName ?? shortHash(item.actorOid)
}

const filters = computed<FilterDef[]>(() => [
  {
    kind: 'select',
    id: 'action',
    label: t('audit.filters.action'),
    options: [{ value: '', label: t('audit.filters.allActions') }, ...Object.keys(ACTION_KEYS).map((value) => ({ value, label: actionLabel(value) }))],
  },
  { kind: 'search', id: 'actor', label: t('audit.filters.actor') },
  { kind: 'search', id: 'target', label: t('audit.filters.target') },
])

const columns = computed<DataColumn<AuditEntry>[]>(() => [
  { key: 'occurredAt', label: t('audit.columns.when'), priority: 1 },
  { key: 'actorName', label: t('audit.columns.who'), priority: 1 },
  { key: 'surface', label: t('audit.columns.surface'), priority: 2 },
  { key: 'action', label: t('audit.columns.action'), priority: 1 },
  { key: 'targetKind', label: t('audit.columns.target'), priority: 1 },
  { key: 'auditId', label: t('audit.details'), priority: 1 },
])

const detailItems = computed(() => {
  const item = entry.value
  if (!item) return []
  return [
    { term: t('audit.detail.when'), value: formatDateTime(item.occurredAt, locale.value) },
    { term: t('audit.detail.actor'), value: item.actorName ? `${item.actorName} (${item.actorOid})` : item.actorOid },
    { term: t('audit.detail.surface'), value: t(`audit.surfaces.${item.surface}`) },
    { term: t('audit.detail.action'), value: actionLabel(item.action) },
    { term: t('audit.detail.target'), value: targetText(item) },
    { term: t('audit.detail.recordedBy'), value: item.recordedByOid },
    { term: t('audit.detail.id'), value: item.auditId },
  ]
})
</script>

<template>
  <section data-testid="audit-log" class="flex flex-col gap-4">
    <p class="text-body-medium text-on-surface-variant">{{ t('audit.intro') }}</p>

    <FilterToolbar v-model="values" :filters="filters" />

    <ResourceState
      :loading="loading"
      :error="error"
      :has-data="shown"
      @retry="load"
    >
      <template v-if="shown">
        <DataTable
          v-if="rows.length > 0"
          :caption="t('audit.title')"
          :columns="columns"
          :rows="rows"
        >
          <template #cell="{ row, column }">
            <template v-if="column.key === 'occurredAt'">{{ formatDateTime(row.occurredAt, locale) }}</template>
            <template v-else-if="column.key === 'actorName'">{{ whoText(row) }}</template>
            <template v-else-if="column.key === 'surface'">{{ t(`audit.surfaces.${row.surface}`) }}</template>
            <template v-else-if="column.key === 'action'">{{ actionLabel(row.action) }}</template>
            <template v-else-if="column.key === 'targetKind'">
              <RouterLink
                v-if="linkOf(row)"
                :to="linkOf(row)!"
                class="text-primary underline underline-offset-2 focus-ring"
              >
                {{ targetText(row) }}
              </RouterLink>
              <template v-else>{{ targetText(row) }}</template>
            </template>
            <MdButton
              v-else-if="column.key === 'auditId'"
              variant="text"
              data-testid="audit-open"
              :aria-current="row.auditId === entryId ? 'true' : undefined"
              @click="openEntry(row)"
            >
              {{ t('audit.details') }}
            </MdButton>
          </template>
        </DataTable>

        <EmptyState
          v-else
          data-testid="audit-empty"
          :variant="filtersApplied ? 'no-matches' : 'no-items'"
          :title="filtersApplied ? t('audit.noMatches') : t('audit.noneYet')"
        >
          <MdButton v-if="filtersApplied" variant="text" data-testid="empty-clear-filters" @click="clearFilters">
            {{ t('filters.clearFilters') }}
          </MdButton>
        </EmptyState>

        <nav v-if="nextCursor || cursor" class="flex flex-wrap gap-2">
          <MdButton v-if="cursor" variant="text" data-testid="audit-first" @click="firstPage">{{ t('audit.firstPage') }}</MdButton>
          <MdButton v-if="nextCursor" variant="tonal" data-testid="audit-next" @click="nextPage">{{ t('audit.nextPage') }}</MdButton>
        </nav>
      </template>
    </ResourceState>

    <SidePanel v-if="entryId" :title="t('audit.detail.title')" :size-class="windowClass" @close="closeEntry">
      <div v-if="entryState === 'loading'" class="pt-2" data-testid="audit-entry-loading">{{ t('audit.entryLoading') }}</div>
      <p v-else-if="entryState === 'missing'" role="alert" data-testid="audit-entry-missing" class="pt-2">{{ t('audit.entryMissing') }}</p>
      <p v-else-if="entryState === 'failed'" role="alert" data-testid="audit-entry-failed" class="pt-2">{{ t('audit.entryFailed') }}</p>
      <div v-else-if="entry" data-testid="audit-detail" class="flex flex-col gap-4 pt-2">
        <DefinitionList :items="detailItems" />
        <RouterLink
          v-if="linkOf(entry)"
          :to="linkOf(entry)!"
          class="text-primary underline underline-offset-2 focus-ring"
        >
          {{ t(entry.targetKind === 'PLAYER' ? 'audit.detail.openPlayer' : 'audit.detail.openCase') }}
        </RouterLink>
        <CopyLink />
      </div>
    </SidePanel>
  </section>
</template>
