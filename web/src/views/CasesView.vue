<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { useApi } from '../api/inject'
import type { CaseStatus } from '../api/types'
import MdButton from '../components/md/MdButton.vue'
import MdChip from '../components/md/MdChip.vue'
import MdDataTable from '../components/md/MdDataTable.vue'
import MdTextField from '../components/md/MdTextField.vue'
import ResourceState from '../components/ResourceState.vue'
import StatusPill from '../components/StatusPill.vue'
import { useResource } from '../composables/useResource'
import { formatDateTime } from '../format'
import { useSessionStore } from '../stores/session'

const STATUSES: CaseStatus[] = ['OPEN', 'IN_REVIEW', 'WAITING', 'CLOSED']
const TAG = /^[a-z0-9-]{1,32}$/

const { t, locale } = useI18n()
const api = useApi()
const session = useSessionStore()

const status = ref<CaseStatus | null>(null)
const mine = ref(false)
const tagInput = ref('')
const tag = ref('')
const tagError = ref('')
const page = ref(0)

const cases = useResource(() =>
  api.cases({
    status: status.value ?? undefined,
    assignee: mine.value ? session.me?.oid : undefined,
    tag: tag.value || undefined,
    page: page.value,
  }),
)

// Any filter starts again at the first page.
watch([status, mine, tag], () => {
  page.value = 0
  void cases.reload()
})
watch(page, () => void cases.reload())

function applyTag(): void {
  const value = tagInput.value.trim().toLowerCase()
  if (value && !TAG.test(value)) {
    tagError.value = t('case.header.tagInvalid')
    return
  }
  tagError.value = ''
  tag.value = value
}

const columns = computed(() => [
  { key: 'shortId' as const, label: t('case.list.columns.case') },
  { key: 'section' as const, label: t('case.list.columns.section') },
  { key: 'punishmentState' as const, label: t('case.list.columns.punishment') },
  { key: 'status' as const, label: t('case.list.columns.status') },
  { key: 'assigneeName' as const, label: t('case.list.columns.assignee') },
  { key: 'priority' as const, label: t('case.list.columns.priority') },
  { key: 'tags' as const, label: t('case.list.columns.tags') },
  { key: 'lastActivityAt' as const, label: t('case.list.columns.activity') },
])
</script>

<template>
  <div class="flex flex-col gap-4">
    <div class="flex flex-wrap items-center gap-2" role="group" :aria-label="t('case.list.filter')">
      <MdChip data-testid="status-all" :label="t('case.list.allStatuses')" :selected="status === null" @click="status = null" />
      <MdChip v-for="value in STATUSES" :key="value" :data-testid="`status-${value}`" :label="t(`case.status.${value}`)" :selected="status === value" @click="status = value" />
      <MdChip data-testid="filter-mine" :label="t('case.list.mine')" :selected="mine" @click="mine = !mine" />
    </div>
    <form class="flex max-w-md items-start gap-2" @submit.prevent="applyTag">
      <div class="flex-1">
        <MdTextField v-model="tagInput" :label="t('case.list.tag')" :error="tagError" data-testid="tag-filter" />
      </div>
      <MdButton type="submit" variant="tonal" class="mt-2" data-testid="apply-tag">{{ t('case.list.applyTag') }}</MdButton>
    </form>

    <ResourceState :loading="cases.loading.value" :error="cases.error.value" :has-data="cases.data.value !== null" @retry="cases.reload">
      <template v-if="cases.data.value">
        <p v-if="cases.data.value.items.length === 0" class="text-body-large text-on-surface-variant" data-testid="cases-empty">{{ t('case.list.empty') }}</p>
        <MdDataTable v-else :label="t('case.list.title')" :columns="columns" :rows="cases.data.value.items">
          <template #cell="{ row, column }">
            <RouterLink
              v-if="column.key === 'shortId'"
              :to="{ name: 'case', params: { reference: row.punishmentIdentifier } }"
              class="text-primary underline underline-offset-2 outline-none focus-visible:outline-2 focus-visible:outline-primary"
            >
              {{ row.shortId }}
            </RouterLink>
            <StatusPill v-else-if="column.key === 'punishmentState'" :label="t(`case.state.${row.punishmentState}`)" />
            <StatusPill v-else-if="column.key === 'status'" :label="t(`case.status.${row.status}`)" tone="primary" />
            <template v-else-if="column.key === 'assigneeName'">{{ row.assigneeName ?? (row.assigneeOid ? `${row.assigneeOid.slice(0, 8)}…` : t('case.header.unassigned')) }}</template>
            <template v-else-if="column.key === 'priority'">{{ t(`case.priority.${row.priority}`) }}</template>
            <template v-else-if="column.key === 'tags'">{{ row.tags.join(', ') }}</template>
            <template v-else-if="column.key === 'lastActivityAt'">{{ formatDateTime(row.lastActivityAt, locale) }}</template>
            <template v-else>{{ row[column.key] }}</template>
          </template>
        </MdDataTable>
        <nav v-if="cases.data.value.totalPages > 1" class="flex items-center gap-2" :aria-label="t('case.list.paging')">
          <MdButton variant="text" :disabled="page === 0" data-testid="prev" @click="page--">{{ t('evidence.paging.previous') }}</MdButton>
          <span class="text-body-medium" aria-live="polite">{{ t('evidence.paging.position', { page: page + 1, total: cases.data.value.totalPages }) }}</span>
          <MdButton variant="text" :disabled="page + 1 >= cases.data.value.totalPages" data-testid="next" @click="page++">{{ t('evidence.paging.next') }}</MdButton>
        </nav>
      </template>
    </ResourceState>
  </div>
</template>
