<script setup lang="ts">
// The staff audit log (ADMIN only, see AdminView). Read only: newest first, one cursor page at a time ("Load more"),
// filtered by action, actor and target. An entry opens in a side sheet with a link to its case or player page. The
// backend is authoritative for access; this screen is only offered to administrators.
import { computed, onMounted, ref, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { useApi } from '../../api/inject'
import type { ApiError, AuditEntry, AuditQuery } from '../../api/types'
import { formatDateTime, shortHash } from '../../format'
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

const { t, locale } = useI18n()
const api = useApi()
const windowClass = useWindowClass()

const values = ref<Record<string, string>>({ action: '', actor: '', target: '' })
const rows = shallowRef<AuditEntry[]>([])
const nextCursor = ref<string | null>(null)
const shown = ref(false)
const loading = ref(true)
const loadingMore = ref(false)
const error = ref<ApiError | null>(null)
const selected = ref<AuditEntry | null>(null)

const filtersApplied = computed(() => Object.values(values.value).some((value) => value !== ''))

// The newest answer wins: a filter changed while a page is on its way must not be overwritten by the old page.
let generation = 0

function queryOf(cursor: string | undefined): AuditQuery {
  const query: AuditQuery = { limit: PAGE_SIZE }
  if (cursor) query.cursor = cursor
  if (values.value.action) query.action = values.value.action
  if (values.value.actor.trim()) query.actorOid = values.value.actor.trim()
  if (values.value.target.trim()) query.targetRef = values.value.target.trim()
  return query
}

/** The first page when `reset` is set (also after a filter change), otherwise the page after the last one shown. */
async function load(reset: boolean): Promise<void> {
  const current = ++generation
  if (reset) loading.value = true
  else loadingMore.value = true
  const result = await api.auditLog(queryOf(reset ? undefined : (nextCursor.value ?? undefined)))
  if (current !== generation) return
  loading.value = false
  loadingMore.value = false
  if (!result.ok) {
    error.value = result.error
    if (reset) {
      rows.value = []
      shown.value = false
    }
    return
  }
  error.value = null
  rows.value = reset ? result.data.items : [...rows.value, ...result.data.items]
  nextCursor.value = result.data.nextCursor ?? null
  shown.value = true
}

onMounted(() => void load(true))
watch(values, () => void load(true), { deep: true })

function clearFilters(): void {
  values.value = { action: '', actor: '', target: '' }
}

function actionLabel(action: string): string {
  const key = ACTION_KEYS[action]
  return key ? t(`audit.actions.${key}`) : action
}

function linkOf(entry: AuditEntry): string | null {
  return entry.panelPath && PANEL_PATH.test(entry.panelPath) ? entry.panelPath : null
}

function targetText(entry: AuditEntry): string {
  const kind = t(`audit.targets.${entry.targetKind}`)
  if (!entry.targetRef) return kind
  // A player is named by its owner hash, shortened like everywhere else; a case by its short id.
  const ref = entry.targetKind === 'PLAYER' ? shortHash(entry.targetRef) : entry.targetRef
  return `${kind} ${ref}`
}

function whoText(entry: AuditEntry): string {
  return entry.actorName ?? shortHash(entry.actorOid)
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
  const entry = selected.value
  if (!entry) return []
  return [
    { term: t('audit.detail.when'), value: formatDateTime(entry.occurredAt, locale.value) },
    { term: t('audit.detail.actor'), value: entry.actorName ? `${entry.actorName} (${entry.actorOid})` : entry.actorOid },
    { term: t('audit.detail.surface'), value: t(`audit.surfaces.${entry.surface}`) },
    { term: t('audit.detail.action'), value: actionLabel(entry.action) },
    { term: t('audit.detail.target'), value: targetText(entry) },
    { term: t('audit.detail.recordedBy'), value: entry.recordedByOid },
    { term: t('audit.detail.id'), value: entry.auditId },
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
      @retry="load(rows.length === 0)"
    >
      <template v-if="shown">
        <DataTable
          v-if="rows.length > 0"
          :caption="t('audit.title')"
          :columns="columns"
          :rows="rows"
          :loading="loadingMore"
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
                class="text-primary underline underline-offset-2 outline-none focus-visible:outline-2 focus-visible:outline-primary"
              >
                {{ targetText(row) }}
              </RouterLink>
              <template v-else>{{ targetText(row) }}</template>
            </template>
            <MdButton v-else-if="column.key === 'auditId'" variant="text" data-testid="audit-open" @click="selected = row">
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

        <nav v-if="nextCursor" class="flex">
          <MdButton variant="tonal" data-testid="audit-next" :disabled="loadingMore" @click="load(false)">
            {{ t('audit.loadMore') }}
          </MdButton>
        </nav>
      </template>
    </ResourceState>

    <SidePanel v-if="selected" :title="t('audit.detail.title')" :size-class="windowClass" @close="selected = null">
      <div data-testid="audit-detail" class="flex flex-col gap-4 pt-2">
        <DefinitionList :items="detailItems" />
        <RouterLink
          v-if="linkOf(selected)"
          :to="linkOf(selected)!"
          class="text-primary underline underline-offset-2 outline-none focus-visible:outline-2 focus-visible:outline-primary"
        >
          {{ t(selected.targetKind === 'PLAYER' ? 'audit.detail.openPlayer' : 'audit.detail.openCase') }}
        </RouterLink>
      </div>
    </SidePanel>
  </section>
</template>
