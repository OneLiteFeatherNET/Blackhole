<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Evidence } from '../../api/types'
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
</script>

<template>
  <div class="flex flex-col gap-3">
    <dl class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-body-medium">
      <template v-for="[label, value] in facts" :key="label">
        <dt class="text-on-surface-variant">{{ label }}</dt>
        <dd class="break-words">{{ value }}</dd>
      </template>
    </dl>
    <table v-if="record.values.length > 0" class="w-full text-left text-body-small" :aria-label="t('evidence.anticheat.values')">
      <caption class="pb-1 text-left text-label-large text-on-surface-variant">{{ t('evidence.anticheat.values') }}</caption>
      <thead class="sr-only">
        <tr>
          <th scope="col">{{ t('evidence.anticheat.name') }}</th>
          <th scope="col">{{ t('evidence.anticheat.value') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="[name, value] in record.values" :key="name" class="border-t border-outline-variant">
          <th scope="row" class="py-1 pr-4 font-normal text-on-surface-variant">{{ name }}</th>
          <td class="py-1 break-words">{{ value }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
