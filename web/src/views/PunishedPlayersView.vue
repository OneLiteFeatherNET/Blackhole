<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { useApi } from '../api/inject'
import type { ActivePunishment, ActivePunishmentQuery, EnforcementKind } from '../api/types'
import MdButton from '../components/md/MdButton.vue'
import MdChip from '../components/md/MdChip.vue'
import MdDataTable from '../components/md/MdDataTable.vue'
import MdSelect from '../components/md/MdSelect.vue'
import MdTextField from '../components/md/MdTextField.vue'
import ResourceState from '../components/ResourceState.vue'
import StatusPill from '../components/StatusPill.vue'
import { useResource } from '../composables/useResource'
import { describeDuration } from '../duration'
import { formatDateTime, shortHash } from '../format'
import { useLookupStore } from '../stores/lookup'
import { useSessionStore } from '../stores/session'

type Term = 'all' | 'permanent' | 'temporary' | 'soon'
type Source = 'all' | 'automatic' | 'manual'

const KINDS: EnforcementKind[] = ['BAN', 'MUTE', 'SERVER']
const TERMS: Term[] = ['all', 'permanent', 'temporary', 'soon']
const SOURCES: Source[] = ['all', 'automatic', 'manual']
/** "Expiring soon" means ending within a day. */
const SOON_MILLIS = 24 * 60 * 60 * 1000

const { t, locale } = useI18n()
const api = useApi()
const session = useSessionStore()
const lookup = useLookupStore()

const kind = ref<EnforcementKind | null>(null)
const term = ref<Term>('all')
const source = ref<Source>('all')
const mine = ref(false)
const section = ref('')
const sort = ref<'CREATED' | 'EXPIRES'>('CREATED')
const direction = ref<'ASC' | 'DESC'>('DESC')
const search = ref('')
const page = ref(0)

function query(): ActivePunishmentQuery {
  return {
    kind: kind.value ?? undefined,
    section: section.value || undefined,
    permanent: term.value === 'permanent' ? true : term.value === 'temporary' || term.value === 'soon' ? false : undefined,
    expiresBefore: term.value === 'soon' ? Date.now() + SOON_MILLIS : undefined,
    automatic: source.value === 'all' ? undefined : source.value === 'automatic',
    actor: mine.value ? session.me?.oid : undefined,
    sort: sort.value,
    direction: direction.value,
    page: page.value,
  }
}

const punished = useResource(() => api.activePunishments(query()))
const sections = useResource(() => api.sections())

// Any filter or sort change starts again at the first page.
watch([kind, term, source, mine, section, sort, direction], () => {
  if (page.value === 0) void punished.reload()
  else page.value = 0
})
watch(page, () => void punished.reload())

const sectionOptions = computed(() => [
  { value: '', label: t('punished.allSections') },
  ...(sections.data.value ?? []).map((entry) => ({ value: entry.key, label: entry.displayName })),
])
const sortOptions = computed(() => (['CREATED', 'EXPIRES'] as const).map((value) => ({ value, label: t(`punished.sort.${value}`) })))

const filtered = computed(() => kind.value !== null || term.value !== 'all' || source.value !== 'all' || mine.value || section.value !== '')

/**
 * Names are not stored (the backend only knows hashed owners), so the search matches the names this
 * session has resolved, the start of the owner hash, the case id, the section and the reason.
 */
function matches(row: ActivePunishment, needle: string): boolean {
  const haystack = [lookup.nameOf(row.owner), row.owner.slice(0, 16), row.shortId.replace('#', ''), row.shortId, row.sectionName, row.section, row.reason]
  return haystack.some((value) => value?.toLowerCase().includes(needle))
}

const visible = computed(() => {
  const needle = search.value.trim().toLowerCase()
  const rows = punished.data.value?.items ?? []
  return needle ? rows.filter((row) => matches(row, needle)) : rows
})

const columns = computed(() => [
  { key: 'owner' as const, label: t('punished.columns.player') },
  { key: 'section' as const, label: t('punished.columns.type') },
  { key: 'reason' as const, label: t('punished.columns.reason') },
  { key: 'createdAt' as const, label: t('punished.columns.since') },
  { key: 'expiresAt' as const, label: t('punished.columns.until') },
  { key: 'automatic' as const, label: t('punished.columns.source') },
  { key: 'actorName' as const, label: t('punished.columns.issuer') },
  { key: 'caseStatus' as const, label: t('punished.columns.case') },
])

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
  <div class="flex flex-col gap-4">
    <div class="flex flex-col gap-3" role="group" :aria-label="t('punished.filters')">
      <div class="flex flex-wrap items-center gap-2" role="group" :aria-label="t('punished.kind.label')">
        <MdChip data-testid="kind-all" :label="t('punished.kind.all')" :selected="kind === null" @click="kind = null" />
        <MdChip v-for="value in KINDS" :key="value" :data-testid="`kind-${value}`" :label="t(`punished.kind.${value}`)" :selected="kind === value" @click="kind = value" />
      </div>
      <div class="flex flex-wrap items-center gap-2" role="group" :aria-label="t('punished.term.label')">
        <MdChip v-for="value in TERMS" :key="value" :data-testid="`term-${value}`" :label="t(`punished.term.${value}`)" :selected="term === value" @click="term = value" />
      </div>
      <div class="flex flex-wrap items-center gap-2" role="group" :aria-label="t('punished.source.label')">
        <MdChip v-for="value in SOURCES" :key="value" :data-testid="`source-${value}`" :label="t(`punished.source.${value}`)" :selected="source === value" @click="source = value" />
        <MdChip data-testid="filter-mine" :label="t('punished.mine')" :selected="mine" @click="mine = !mine" />
      </div>
      <div class="flex flex-wrap items-start gap-4">
        <div class="w-64" data-testid="section-filter">
          <MdSelect v-model="section" :label="t('punished.section')" :options="sectionOptions" />
        </div>
        <div class="w-56" data-testid="sort-select">
          <MdSelect v-model="sort" :label="t('punished.sort.label')" :options="sortOptions" />
        </div>
        <MdButton variant="tonal" class="mt-2" data-testid="toggle-direction" @click="direction = direction === 'DESC' ? 'ASC' : 'DESC'">
          {{ t(`punished.sort.${direction}`) }}
        </MdButton>
      </div>
      <div class="max-w-md">
        <MdTextField v-model="search" :label="t('punished.search.label')" :supporting-text="t('punished.search.hint')" data-testid="search" />
      </div>
    </div>

    <ResourceState :loading="punished.loading.value" :error="punished.error.value" :has-data="punished.data.value !== null" @retry="punished.reload">
      <template v-if="punished.data.value">
        <p class="text-body-medium text-on-surface-variant" aria-live="polite" data-testid="total">{{ t('punished.total', { count: punished.data.value.totalSize }) }}</p>
        <p v-if="punished.data.value.items.length === 0" class="text-body-large text-on-surface-variant" data-testid="punished-empty">
          {{ filtered ? t('punished.emptyFiltered') : t('punished.empty') }}
        </p>
        <p v-else-if="visible.length === 0" class="text-body-large text-on-surface-variant" data-testid="punished-no-match">{{ t('punished.emptySearch') }}</p>
        <MdDataTable v-else :label="t('punished.title')" :columns="columns" :rows="visible">
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
            <template v-else>{{ row[column.key] }}</template>
          </template>
        </MdDataTable>
        <nav v-if="punished.data.value.totalPages > 1" class="flex items-center gap-2" :aria-label="t('punished.paging')">
          <MdButton variant="text" :disabled="page === 0" data-testid="prev" @click="page--">{{ t('evidence.paging.previous') }}</MdButton>
          <span class="text-body-medium" aria-live="polite">{{ t('evidence.paging.position', { page: page + 1, total: punished.data.value.totalPages }) }}</span>
          <MdButton variant="text" :disabled="page + 1 >= punished.data.value.totalPages" data-testid="next" @click="page++">{{ t('evidence.paging.next') }}</MdButton>
        </nav>
      </template>
    </ResourceState>
  </div>
</template>
