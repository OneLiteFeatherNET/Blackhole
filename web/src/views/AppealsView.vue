<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { useApi } from '../api/inject'
import type { Appeal, AppealStatus } from '../api/types'
import AppealDecisionDialog from '../components/appeals/AppealDecisionDialog.vue'
import MdButton from '../components/md/MdButton.vue'
import MdChip from '../components/md/MdChip.vue'
import MdDataTable from '../components/md/MdDataTable.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import ResourceState from '../components/ResourceState.vue'
import StatusPill from '../components/StatusPill.vue'
import { useResource } from '../composables/useResource'
import { formatDateTime, shortHash } from '../format'
import { useSessionStore } from '../stores/session'

// Same set the dashboard counts as pending; only the last two can be decided.
const PENDING: AppealStatus[] = ['SUBMITTED', 'ELIGIBLE_PENDING_REVIEW', 'IN_REVIEW']
const DECIDABLE: AppealStatus[] = ['ELIGIBLE_PENDING_REVIEW', 'IN_REVIEW']

const { t, locale } = useI18n()
const api = useApi()
const session = useSessionStore()

const appeals = useResource(() => api.appeals())
const showAll = ref(false)
const deciding = ref<Appeal | null>(null)
const message = ref('')

const visible = computed(() => (appeals.data.value ?? []).filter((entry) => showAll.value || PENDING.includes(entry.status)))

const columns = computed(() => [
  { key: 'createdAt' as const, label: t('appeals.columns.when') },
  { key: 'appellantHash' as const, label: t('appeals.columns.appellant') },
  { key: 'punishmentIdentifier' as const, label: t('appeals.columns.punishment') },
  { key: 'statement' as const, label: t('appeals.columns.statement') },
  { key: 'status' as const, label: t('appeals.columns.status') },
  { key: 'identifier' as const, label: t('appeals.columns.actions') },
])

async function done(text: string): Promise<void> {
  deciding.value = null
  message.value = text
  await appeals.reload()
}
</script>

<template>
  <ResourceState
    :loading="appeals.loading.value"
    :error="appeals.error.value"
    :has-data="appeals.data.value !== null"
    @retry="appeals.reload"
  >
    <div class="flex flex-col gap-4">
      <div class="flex flex-wrap items-center gap-2" role="group" :aria-label="t('appeals.filter')">
        <MdChip data-testid="filter-pending" :label="t('appeals.filterPending')" :selected="!showAll" @click="showAll = false" />
        <MdChip data-testid="filter-all" :label="t('appeals.filterAll')" :selected="showAll" @click="showAll = true" />
      </div>

      <p v-if="visible.length === 0" class="text-body-large text-on-surface-variant">{{ t('appeals.empty') }}</p>
      <MdDataTable v-else :label="t('appeals.title')" :columns="columns" :rows="visible">
        <template #cell="{ row, column }">
          <template v-if="column.key === 'createdAt'">{{ formatDateTime(row.createdAt, locale) }}</template>
          <RouterLink
            v-else-if="column.key === 'appellantHash'"
            :to="`/players/${row.appellantHash}`"
            :title="row.appellantHash"
            class="text-primary underline underline-offset-2 outline-none focus-visible:outline-2 focus-visible:outline-primary"
          >
            {{ shortHash(row.appellantHash) }}
          </RouterLink>
          <span v-else-if="column.key === 'punishmentIdentifier'" :title="row.punishmentIdentifier">{{ row.punishmentIdentifier.slice(0, 8) }}…</span>
          <p v-else-if="column.key === 'statement'" class="max-w-xs break-words">{{ row.statement }}</p>
          <StatusPill
            v-else-if="column.key === 'status'"
            :label="t(`appeals.status.${row.status}`)"
            :tone="DECIDABLE.includes(row.status) ? 'tertiary' : 'neutral'"
          />
          <template v-else-if="column.key === 'identifier'">
            <MdButton v-if="session.moderator && DECIDABLE.includes(row.status)" variant="outlined" :data-testid="`decide-${row.identifier}`" @click="deciding = row">
              {{ t('appeals.decide.open') }}
            </MdButton>
          </template>
          <template v-else>{{ row[column.key] }}</template>
        </template>
      </MdDataTable>
    </div>
  </ResourceState>

  <AppealDecisionDialog :open="deciding !== null" :appeal="deciding" @close="deciding = null" @done="done" />
  <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
</template>
