<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { useApi } from '../api/inject'
import type { InboxItem } from '../api/types'
import MdCard from '../components/md/MdCard.vue'
import MdDataTable from '../components/md/MdDataTable.vue'
import PageShell from '../components/PageShell.vue'
import ResourceState from '../components/ResourceState.vue'
import StatusPill from '../components/StatusPill.vue'
import { useResource } from '../composables/useResource'
import { formatDateTime, shortHash } from '../format'

// The front page of the panel: what needs attention now (the inbox), then the automatic escalations and the
// active punishments, which the dashboard used to show.
const { t, locale } = useI18n()
const api = useApi()
const inbox = useResource(() => api.inbox())
const summary = useResource(() => api.dashboard())

type SectionKey = 'needsAnswer' | 'openAppeals' | 'newReports' | 'unassignedCases'
const SECTION_KEYS: SectionKey[] = ['needsAnswer', 'openAppeals', 'newReports', 'unassignedCases']

/** A case of the appeal chat or of an unassigned case; a report has its own page. */
function caseLink(item: InboxItem): string {
  return item.reference ? `/cases/${item.reference}` : '/cases'
}

/** Where a section's items lead, and the filtered full list behind its "Show all" link. */
const SECTION_LINKS: Record<SectionKey, { all: string; item: (item: InboxItem) => string }> = {
  needsAnswer: { all: '/appeals', item: caseLink },
  openAppeals: { all: '/appeals', item: caseLink },
  newReports: { all: '/reports', item: (item) => `/reports/${item.id}` },
  unassignedCases: { all: '/cases', item: caseLink },
}

const peak = computed(() => Math.max(1, ...(summary.data.value?.activePunishmentsBySection ?? []).map((s) => s.active)))

const columns = computed(() => [
  { key: 'section' as const, label: t('dashboard.escalations.section') },
  { key: 'owner' as const, label: t('dashboard.escalations.player') },
  { key: 'createdAt' as const, label: t('dashboard.escalations.when') },
  { key: 'lifted' as const, label: t('dashboard.escalations.status') },
])
</script>

<template>
  <PageShell :title="t('inbox.title')">
    <ResourceState
      :loading="inbox.loading.value"
      :error="inbox.error.value"
      :has-data="inbox.data.value !== null"
      @retry="inbox.reload"
    >
      <div v-if="inbox.data.value" class="flex flex-col gap-6">
        <section
          v-for="key in SECTION_KEYS"
          :key="key"
          :data-testid="`section-${key}`"
          :aria-labelledby="`${key}-heading`"
          class="flex flex-col gap-3"
        >
          <div class="flex flex-wrap items-baseline justify-between gap-2">
            <h2 :id="`${key}-heading`" class="text-title-medium">{{ t(`inbox.${key}`) }}</h2>
            <span data-testid="section-count" class="text-headline-small">{{ inbox.data.value[key].count }}</span>
          </div>
          <ul v-if="inbox.data.value[key].items.length" class="flex flex-col divide-y">
            <li
              v-for="item in inbox.data.value[key].items"
              :key="item.id"
              class="flex flex-wrap items-center justify-between gap-2 py-2"
            >
              <RouterLink
                :to="SECTION_LINKS[key].item(item)"
                class="text-primary underline underline-offset-2 outline-none focus-visible:outline-2 focus-visible:outline-primary"
              >
                {{ item.reference ?? shortHash(item.id) }}
              </RouterLink>
              <span class="text-body-medium text-on-surface-variant">{{ formatDateTime(item.since, locale) }}</span>
            </li>
          </ul>
          <p v-else class="text-body-large text-on-surface-variant">{{ t('inbox.empty') }}</p>
          <RouterLink
            data-testid="show-all"
            :to="SECTION_LINKS[key].all"
            class="self-start text-label-large text-primary underline underline-offset-2 outline-none focus-visible:outline-2 focus-visible:outline-primary"
          >
            {{ t('inbox.showAll') }}
          </RouterLink>
        </section>
      </div>
    </ResourceState>

    <ResourceState
      :loading="summary.loading.value"
      :error="summary.error.value"
      :has-data="summary.data.value !== null"
      @retry="summary.reload"
    >
      <div v-if="summary.data.value" class="flex flex-col gap-6">
        <section aria-labelledby="active-heading">
          <h2 id="active-heading" class="mb-3 text-title-medium">{{ t('dashboard.active') }}</h2>
          <ul class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            <li v-for="entry in summary.data.value.activePunishmentsBySection" :key="entry.section">
              <MdCard variant="outlined" :data-testid="`section-${entry.section}`">
                <div class="flex items-baseline justify-between gap-2">
                  <span class="text-title-medium">{{ entry.section }}</span>
                  <span class="text-headline-small">{{ entry.active }}</span>
                </div>
                <div class="mt-3 h-1 rounded-md-full bg-surface-container-highest" aria-hidden="true">
                  <div class="h-full rounded-md-full bg-primary" :style="{ width: `${(entry.active / peak) * 100}%` }" />
                </div>
              </MdCard>
            </li>
          </ul>
        </section>

        <section aria-labelledby="escalations-heading">
          <h2 id="escalations-heading" class="mb-3 text-title-medium">{{ t('dashboard.latest') }}</h2>
          <p v-if="summary.data.value.latestEscalations.length === 0" class="text-body-large text-on-surface-variant">
            {{ t('dashboard.empty') }}
          </p>
          <MdDataTable
            v-else
            :label="t('dashboard.latest')"
            :columns="columns"
            :rows="summary.data.value.latestEscalations"
          >
            <template #cell="{ row, column }">
              <RouterLink
                v-if="column.key === 'owner'"
                :to="`/players/${row.owner}`"
                :title="row.owner"
                class="text-primary underline underline-offset-2 outline-none focus-visible:outline-2 focus-visible:outline-primary"
              >
                {{ shortHash(row.owner) }}
              </RouterLink>
              <template v-else-if="column.key === 'createdAt'">
                {{ row.createdAt === undefined ? '' : formatDateTime(row.createdAt, locale) }}
              </template>
              <StatusPill v-else-if="column.key === 'lifted' && row.lifted" :label="t('dashboard.escalations.lifted')" />
              <template v-else-if="column.key === 'lifted'">{{ t('dashboard.escalations.standing') }}</template>
              <template v-else>{{ row[column.key] }}</template>
            </template>
          </MdDataTable>
        </section>
      </div>
    </ResourceState>
  </PageShell>
</template>
