<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ApiError, EloEvent } from '../../api/types'
import { formatDateTime } from '../../format'
import MdDataTable from '../md/MdDataTable.vue'
import MdProgress from '../md/MdProgress.vue'
import MdSelect from '../md/MdSelect.vue'

defineProps<{
  sections: { value: string; label: string }[]
  events: EloEvent[] | null
  loading: boolean
  error: ApiError | null
}>()
const section = defineModel<string>('section', { required: true })

const { t, locale } = useI18n()

const columns = computed(() => [
  { key: 'createdAt' as const, label: t('player.columns.when') },
  { key: 'delta' as const, label: t('player.columns.delta') },
  { key: 'reasonCode' as const, label: t('player.columns.reason') },
  { key: 'resultingScore' as const, label: t('player.columns.score') },
])
</script>

<template>
  <div class="flex flex-col gap-4">
    <MdSelect v-model="section" class="w-full max-w-xs" :label="t('player.columns.section')" :options="sections" />
    <MdProgress v-if="loading" :label="t('state.loading')" />
    <p v-if="error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">{{ error.message }}</p>
    <p v-else-if="events && events.length === 0" class="text-body-large text-on-surface-variant">{{ t('player.noEloHistory') }}</p>
    <MdDataTable v-else-if="events" :label="t('player.eloHistory')" :columns="columns" :rows="events">
      <template #cell="{ row, column }">
        <template v-if="column.key === 'createdAt'">{{ formatDateTime(row.createdAt, locale) }}</template>
        <template v-else-if="column.key === 'delta'">{{ row.delta > 0 ? `+${row.delta}` : row.delta }}</template>
        <template v-else-if="column.key === 'reasonCode'">{{ t(`elo.reason.${row.reasonCode}`) }}</template>
        <template v-else>{{ row[column.key] }}</template>
      </template>
    </MdDataTable>
  </div>
</template>
