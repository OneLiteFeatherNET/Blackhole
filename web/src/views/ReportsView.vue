<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { useApi } from '../api/inject'
import type { Report, ReportStatus } from '../api/types'
import MdButton from '../components/md/MdButton.vue'
import MdChip from '../components/md/MdChip.vue'
import MdDataTable from '../components/md/MdDataTable.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import ReportResolveDialog from '../components/reports/ReportResolveDialog.vue'
import ResourceState from '../components/ResourceState.vue'
import StatusPill from '../components/StatusPill.vue'
import { useResource } from '../composables/useResource'
import { formatDateTime, shortHash } from '../format'
import { useSessionStore } from '../stores/session'

const OPEN: ReportStatus[] = ['OPEN', 'UNDER_REVIEW']

const { t, locale } = useI18n()
const api = useApi()
const session = useSessionStore()

const reports = useResource(() => api.reports())
const showAll = ref(false)
const resolving = ref<Report | null>(null)
const message = ref('')

const visible = computed(() =>
  (reports.data.value ?? []).filter((entry) => showAll.value || OPEN.includes(entry.status ?? 'OPEN')),
)
const isOpen = (entry: Report) => OPEN.includes(entry.status ?? 'OPEN')

const columns = computed(() => [
  { key: 'createdAt' as const, label: t('reports.columns.when') },
  { key: 'category' as const, label: t('reports.columns.category') },
  { key: 'reportedHash' as const, label: t('reports.columns.reported') },
  { key: 'reporterHash' as const, label: t('reports.columns.reporter') },
  { key: 'description' as const, label: t('reports.columns.description') },
  { key: 'status' as const, label: t('reports.columns.status') },
  { key: 'identifier' as const, label: t('reports.columns.actions') },
])

async function done(text: string): Promise<void> {
  resolving.value = null
  message.value = text
  await reports.reload()
}
</script>

<template>
  <ResourceState
    :loading="reports.loading.value"
    :error="reports.error.value"
    :has-data="reports.data.value !== null"
    @retry="reports.reload"
  >
    <div class="flex flex-col gap-4">
      <div class="flex flex-wrap items-center gap-2" role="group" :aria-label="t('reports.filter')">
        <MdChip data-testid="filter-open" :label="t('reports.filterOpen')" :selected="!showAll" @click="showAll = false" />
        <MdChip data-testid="filter-all" :label="t('reports.filterAll')" :selected="showAll" @click="showAll = true" />
      </div>

      <p v-if="visible.length === 0" class="text-body-large text-on-surface-variant">{{ t('reports.empty') }}</p>
      <MdDataTable v-else :label="t('reports.title')" :columns="columns" :rows="visible">
        <template #cell="{ row, column }">
          <template v-if="column.key === 'createdAt'">{{ formatDateTime(row.createdAt, locale) }}</template>
          <template v-else-if="column.key === 'category'">{{ t(`reports.category.${row.category}`) }}</template>
          <RouterLink
            v-else-if="column.key === 'reportedHash'"
            :to="`/players/${row.reportedHash}`"
            :title="row.reportedHash"
            class="text-primary underline underline-offset-2 outline-none focus-visible:outline-2 focus-visible:outline-primary"
          >
            {{ shortHash(row.reportedHash) }}
          </RouterLink>
          <span v-else-if="column.key === 'reporterHash'" :title="row.reporterHash">{{ shortHash(row.reporterHash) }}</span>
          <p v-else-if="column.key === 'description'" class="max-w-xs break-words">{{ row.description }}</p>
          <StatusPill
            v-else-if="column.key === 'status'"
            :label="t(`reports.status.${row.status ?? 'OPEN'}`)"
            :tone="isOpen(row) ? 'tertiary' : 'neutral'"
          />
          <template v-else-if="column.key === 'identifier'">
            <MdButton v-if="session.moderator && isOpen(row) && row.identifier" variant="outlined" :data-testid="`resolve-${row.identifier}`" @click="resolving = row">
              {{ t('reports.resolve.open') }}
            </MdButton>
          </template>
          <template v-else>{{ row[column.key] }}</template>
        </template>
      </MdDataTable>
    </div>
  </ResourceState>

  <ReportResolveDialog :open="resolving !== null" :report="resolving" @close="resolving = null" @done="done" />
  <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
</template>
