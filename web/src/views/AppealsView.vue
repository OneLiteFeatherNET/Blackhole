<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { type LocationQuery, useRoute, useRouter } from 'vue-router'
import { useApi } from '../api/inject'
import type { Appeal, AppealStatus } from '../api/types'
import AppealDecisionDialog from '../components/appeals/AppealDecisionDialog.vue'
import DataTable, { type DataColumn } from '../components/DataTable.vue'
import EmptyState from '../components/EmptyState.vue'
import FilterToolbar, { type FilterDef } from '../components/FilterToolbar.vue'
import PageShell from '../components/PageShell.vue'
import ResourceState from '../components/ResourceState.vue'
import StatusPill from '../components/StatusPill.vue'
import TextLink from '../components/TextLink.vue'
import MdButton from '../components/md/MdButton.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import { useResource } from '../composables/useResource'
import { formatDateTime, shortHash } from '../format'
import { useSessionStore } from '../stores/session'

// Same set the dashboard counts as pending; only the last two can be decided.
const PENDING: AppealStatus[] = ['SUBMITTED', 'ELIGIBLE_PENDING_REVIEW', 'IN_REVIEW']
const DECIDABLE: AppealStatus[] = ['ELIGIBLE_PENDING_REVIEW', 'IN_REVIEW']

const { t, locale } = useI18n()
const api = useApi()
const session = useSessionStore()
const route = useRoute()
const router = useRouter()

// The one filter is pending or all. Pending is the default, so the route query only carries `show=all`; a reload or
// Back returns to the same view.
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
    label: t('appeals.filter'),
    options: [
      { value: '', label: t('appeals.filterPending'), testid: 'filter-pending' },
      { value: 'all', label: t('appeals.filterAll'), testid: 'filter-all' },
    ],
  },
])

const appeals = useResource(() => api.appeals())
const deciding = ref<Appeal | null>(null)
const message = ref('')
/** Identifiers of the pending appeals whose newest conversation entry is a message from the player. */
const unanswered = ref<ReadonlySet<string>>(new Set())

const visible = computed(() => (appeals.data.value ?? []).filter((entry) => showAll.value || PENDING.includes(entry.status)))

const columns = computed<DataColumn<Appeal>[]>(() => [
  { key: 'createdAt', label: t('appeals.columns.when'), priority: 2 },
  { key: 'appellantHash', label: t('appeals.columns.appellant'), priority: 2 },
  { key: 'punishmentIdentifier', label: t('appeals.columns.punishment'), priority: 3 },
  { key: 'statement', label: t('appeals.columns.statement'), priority: 1 },
  { key: 'status', label: t('appeals.columns.status'), priority: 1 },
  { key: 'identifier', label: t('appeals.columns.actions'), priority: 1, align: 'end' },
])

let generation = 0

// One small read per pending appeal: its newest message. A failed read just shows no marker. The reads run once per
// load of the list; changing the filter does not read again.
watch(
  () => appeals.data.value,
  async (list) => {
    const mine = ++generation
    const pending = (list ?? []).filter((entry) => PENDING.includes(entry.status))
    const answers = await Promise.all(
      pending.map(async (entry) => {
        const result = await api.caseTimeline(entry.punishmentIdentifier, { types: ['message.added'], order: 'desc', size: 1 })
        return result.ok && result.data.items[0]?.actor.trust === 'PLAYER' ? entry.identifier : null
      }),
    )
    if (mine === generation) unanswered.value = new Set(answers.filter((id): id is string => id !== null))
  },
  { immediate: true },
)

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
  deciding.value = null
  message.value = text
  await appeals.reload()
}
</script>

<template>
  <PageShell :title="t('appeals.title')">
    <template #toolbar>
      <FilterToolbar v-model="values" :filters="filters" />
    </template>

    <DataTable
      v-if="appeals.loading.value && appeals.data.value === null"
      :caption="t('appeals.title')"
      :columns="columns"
      :rows="[]"
      loading
    />

    <ResourceState :loading="appeals.loading.value" :error="appeals.error.value" :has-data="appeals.data.value !== null" @retry="appeals.reload">
      <template v-if="appeals.data.value">
        <p role="status" aria-live="polite" class="sr-only" data-testid="unanswered-status">
          {{ unanswered.size > 0 ? t('appeals.unansweredCount', { count: unanswered.size }, unanswered.size) : '' }}
        </p>

        <DataTable v-if="visible.length > 0" :caption="t('appeals.title')" :columns="columns" :rows="visible">
          <template #cell="{ row, column }">
            <template v-if="column.key === 'createdAt'">{{ formatDateTime(row.createdAt, locale) }}</template>
            <TextLink v-else-if="column.key === 'appellantHash'" :to="`/players/${row.appellantHash}`" :title="row.appellantHash">
              {{ shortHash(row.appellantHash) }}
            </TextLink>
            <span v-else-if="column.key === 'punishmentIdentifier'" :title="row.punishmentIdentifier">{{ row.punishmentIdentifier.slice(0, 8) }}…</span>
            <p v-else-if="column.key === 'statement'" class="max-w-xs break-words">{{ row.statement }}</p>
            <div v-else-if="column.key === 'status'" class="flex flex-wrap items-center gap-2">
              <StatusPill :label="t(`appeals.status.${row.status}`)" :tone="DECIDABLE.includes(row.status) ? 'tertiary' : 'neutral'" />
              <StatusPill
                v-if="unanswered.has(row.identifier)"
                :label="t('appeals.unanswered')"
                tone="tertiary"
                :title="t('appeals.unansweredHint')"
                :data-testid="`unanswered-${row.identifier}`"
              />
            </div>
            <!-- One primary action: the decision. The case is a link beside it, not a second button. -->
            <div v-else-if="column.key === 'identifier'" class="flex flex-wrap items-center justify-end gap-2">
              <TextLink :to="`/cases/${row.punishmentIdentifier}`" :data-testid="`case-${row.identifier}`">
                {{ t('appeals.openCase') }}
              </TextLink>
              <MdButton
                v-if="session.moderator && DECIDABLE.includes(row.status)"
                variant="tonal"
                :data-testid="`decide-${row.identifier}`"
                @click="deciding = row"
              >
                {{ t('appeals.decide.open') }}
              </MdButton>
            </div>
            <template v-else>{{ row[column.key] }}</template>
          </template>
        </DataTable>

        <EmptyState
          v-else
          data-testid="appeals-empty"
          :variant="showAll ? 'no-items' : 'no-matches'"
          :title="showAll ? t('appeals.noneYet') : t('appeals.empty')"
        >
          <MdButton v-if="!showAll" variant="text" data-testid="empty-show-all" @click="showEverything">
            {{ t('appeals.showAll') }}
          </MdButton>
        </EmptyState>
      </template>
    </ResourceState>
  </PageShell>

  <AppealDecisionDialog :open="deciding !== null" :appeal="deciding" @close="deciding = null" @done="done" />
  <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
</template>
