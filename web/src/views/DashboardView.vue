<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { useApi } from '../api/inject'
import MdCard from '../components/md/MdCard.vue'
import MdDataTable from '../components/md/MdDataTable.vue'
import ResourceState from '../components/ResourceState.vue'
import StatusPill from '../components/StatusPill.vue'
import { useResource } from '../composables/useResource'
import { formatDateTime, shortHash } from '../format'

const { t, locale } = useI18n()
const api = useApi()
const summary = useResource(() => api.dashboard())

const peak = computed(() => Math.max(1, ...(summary.data.value?.activePunishmentsBySection ?? []).map((s) => s.active)))

const columns = computed(() => [
  { key: 'section' as const, label: t('dashboard.escalations.section') },
  { key: 'owner' as const, label: t('dashboard.escalations.player') },
  { key: 'createdAt' as const, label: t('dashboard.escalations.when') },
  { key: 'lifted' as const, label: t('dashboard.escalations.status') },
])
</script>

<template>
  <ResourceState
    :loading="summary.loading.value"
    :error="summary.error.value"
    :has-data="summary.data.value !== null"
    @retry="summary.reload"
  >
    <div v-if="summary.data.value" class="flex flex-col gap-6">
      <section :aria-label="t('dashboard.counters')" class="grid gap-4 sm:grid-cols-3">
        <RouterLink to="/reports" class="md-state rounded-md-md">
          <MdCard variant="filled" class="h-full">
            <p class="text-label-large text-on-surface-variant">{{ t('dashboard.openReports') }}</p>
            <p data-testid="open-reports" class="text-display-small">{{ summary.data.value.openReports ?? 0 }}</p>
          </MdCard>
        </RouterLink>
        <RouterLink to="/appeals" class="md-state rounded-md-md">
          <MdCard variant="filled" class="h-full">
            <p class="text-label-large text-on-surface-variant">{{ t('dashboard.pendingAppeals') }}</p>
            <p data-testid="pending-appeals" class="text-display-small">{{ summary.data.value.pendingAppeals ?? 0 }}</p>
          </MdCard>
        </RouterLink>
        <MdCard variant="filled">
          <p class="text-label-large text-on-surface-variant">{{ t('dashboard.escalations7d') }}</p>
          <p data-testid="escalations-7d" class="text-display-small">{{ summary.data.value.escalationsLast7Days ?? 0 }}</p>
        </MdCard>
      </section>

      <section aria-labelledby="active-heading">
        <h2 id="active-heading" class="mb-3 text-title-large">{{ t('dashboard.active') }}</h2>
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
        <h2 id="escalations-heading" class="mb-3 text-title-large">{{ t('dashboard.latest') }}</h2>
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
</template>
