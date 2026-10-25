<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Evidence } from '../../api/types'
import DataTable, { type DataColumn } from '../DataTable.vue'
import { anticheatOf } from './payload'

const props = defineProps<{ item: Evidence }>()

const { t } = useI18n()
const record = computed(() => anticheatOf(props.item))

const facts = computed(() =>
  [
    [t('evidence.anticheat.check'), record.value.check],
    [t('evidence.anticheat.violationLevel'), record.value.violationLevel === null ? null : String(record.value.violationLevel)],
    [t('evidence.anticheat.server'), record.value.server],
    [t('evidence.anticheat.detail'), record.value.detail],
  ].filter((fact): fact is [string, string] => fact[1] !== null),
)

const valueColumns = computed<DataColumn<{ name: string; value: string }>[]>(() => [
  { key: 'name', label: t('evidence.anticheat.name'), priority: 1 },
  { key: 'value', label: t('evidence.anticheat.value'), priority: 1 },
])
const valueRows = computed(() => record.value.values.map(([name, value]) => ({ name, value })))
</script>

<template>
  <div class="flex flex-col gap-3">
    <dl class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-body-medium">
      <template v-for="[label, value] in facts" :key="label">
        <dt class="text-on-surface-variant">{{ label }}</dt>
        <dd class="break-words">{{ value }}</dd>
      </template>
    </dl>
    <DataTable v-if="valueRows.length > 0" :caption="t('evidence.anticheat.values')" caption-visible :columns="valueColumns" :rows="valueRows" />
  </div>
</template>
