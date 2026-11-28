<script setup lang="ts">
// One cell of a case row, shared by the flat table and the cases grouped by player, so both views show a case the same
// way. The column decides what is shown; the row is one case of the list. Pill tones come from caseTones.ts, the same
// map the case bar uses.
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute } from 'vue-router'
import { formatDateTime } from '../../format'
import type { CaseListItem } from '../../api/types'
import type { DataColumn } from '../DataTable.vue'
import StatusPill from '../StatusPill.vue'
import { CASE_STATUS_TONE, PRIORITY_TONE, punishmentStateTone } from './caseTones'

defineProps<{ row: CaseListItem; column: DataColumn<CaseListItem> }>()

const { t, locale } = useI18n()
// The case opens with the list query kept, so Back returns to the same filtered list.
const route = useRoute()
</script>

<template>
  <RouterLink
    v-if="column.key === 'shortId'"
    :to="{ name: 'case', params: { reference: row.punishmentIdentifier }, query: route.query }"
    class="text-primary underline underline-offset-2 focus-ring"
  >
    {{ row.shortId }}
  </RouterLink>
  <template v-else-if="column.key === 'playerName'">{{ row.playerName ?? t('case.list.unknownPlayer', { hash: row.ownerShort }) }}</template>
  <StatusPill
    v-else-if="column.key === 'punishmentState'"
    :label="t(`case.state.${row.punishmentState}`)"
    :tone="punishmentStateTone(row.punishmentState)"
  />
  <StatusPill v-else-if="column.key === 'status'" :label="t(`case.status.${row.status}`)" :tone="CASE_STATUS_TONE[row.status]" />
  <template v-else-if="column.key === 'assigneeName'">{{ row.assigneeName ?? (row.assigneeOid ? `${row.assigneeOid.slice(0, 8)}…` : t('case.header.unassigned')) }}</template>
  <StatusPill v-else-if="column.key === 'priority'" :label="t(`case.priority.${row.priority}`)" :tone="PRIORITY_TONE[row.priority]" />
  <ul v-else-if="column.key === 'tags'" class="flex flex-wrap gap-1" :aria-label="t('case.header.tags')">
    <li
      v-for="tag in row.tags"
      :key="tag"
      class="inline-flex h-6 items-center rounded-md-sm border border-outline px-2 text-label-medium whitespace-nowrap text-on-surface-variant"
    >
      {{ tag }}
    </li>
  </ul>
  <template v-else-if="column.key === 'lastActivityAt'">{{ formatDateTime(row.lastActivityAt, locale) }}</template>
  <template v-else>{{ row[column.key] }}</template>
</template>
