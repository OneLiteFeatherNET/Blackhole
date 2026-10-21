<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Punishment } from '../../api/types'
import { formatDateTime } from '../../format'
import MdDataTable from '../md/MdDataTable.vue'
import StatusPill from '../StatusPill.vue'
import { reasonOf, stepLabel } from './punishmentInfo'

defineProps<{ history: Punishment[] }>()

const { t, locale } = useI18n()

const columns = computed(() => [
  { key: 'createdAt' as const, label: t('player.columns.when') },
  { key: 'section' as const, label: t('player.columns.section') },
  { key: 'template' as const, label: t('player.columns.reason') },
  { key: 'stepIndex' as const, label: t('player.columns.step') },
  { key: 'eloCost' as const, label: t('player.columns.cost') },
  { key: 'lifted' as const, label: t('player.columns.status') },
])
</script>

<template>
  <p v-if="history.length === 0" class="text-body-large text-on-surface-variant">{{ t('player.noHistory') }}</p>
  <MdDataTable v-else :label="t('player.history')" :columns="columns" :rows="history">
    <template #cell="{ row, column }">
      <template v-if="column.key === 'createdAt'">{{ row.createdAt ? formatDateTime(row.createdAt, locale) : '' }}</template>
      <template v-else-if="column.key === 'template'">{{ reasonOf(row) }}</template>
      <template v-else-if="column.key === 'stepIndex'">{{ stepLabel(row) ?? '' }}</template>
      <template v-else-if="column.key === 'eloCost'">{{ row.eloCost ?? '' }}</template>
      <template v-else-if="column.key === 'lifted'">
        <StatusPill v-if="row.lifted" :label="t('player.lifted')" />
        <StatusPill v-else-if="row.permanent" :label="t('player.permanent')" tone="error" />
        <StatusPill v-else :label="t('player.ended')" />
      </template>
      <template v-else>{{ row[column.key] }}</template>
    </template>
  </MdDataTable>
</template>
