<script setup lang="ts">
// The reports filed against the player, each linking to its own report page (the report list keeps its own filters).
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { useApi } from '../../api/inject'
import type { Report } from '../../api/types'
import { useResource } from '../../composables/useResource'
import { formatDateTime } from '../../format'
import ResourceState from '../ResourceState.vue'

const props = defineProps<{ owner: string }>()

const { t, locale } = useI18n()
const api = useApi()
const reports = useResource(() => api.reportsAgainst(props.owner))
const rows = computed<Report[]>(() => reports.data.value ?? [])
</script>

<template>
  <section aria-labelledby="reports-heading">
    <h2 id="reports-heading" class="mb-3 text-title-medium">{{ t('player.tabs.reports') }}</h2>
    <ResourceState :loading="reports.loading.value" :error="reports.error.value" :has-data="reports.data.value !== null" @retry="reports.reload">
      <p v-if="rows.length === 0" class="text-body-large text-on-surface-variant">{{ t('player.reports.empty') }}</p>
      <ul v-else data-testid="player-reports" class="flex flex-col gap-2">
        <li v-for="report in rows" :key="String(report.identifier)" class="flex flex-wrap items-center gap-3">
          <RouterLink :to="{ name: 'report', params: { id: String(report.identifier) } }" class="inline-flex min-h-12 items-center text-primary underline underline-offset-2 focus-ring">
            {{ t('player.reports.open') }}
          </RouterLink>
          <span class="text-body-medium text-on-surface-variant">{{ report.category }} · {{ t(`reports.status.${report.status}`) }} · {{ formatDateTime(report.createdAt, locale) }}</span>
        </li>
      </ul>
    </ResourceState>
  </section>
</template>
