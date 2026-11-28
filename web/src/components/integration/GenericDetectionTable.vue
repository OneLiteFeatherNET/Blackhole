<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { IntegrationDetection } from './types'

// The fallback for every integration kind without its own component: each validated payload field as a label and a
// text value. Everything is interpolated as text; nothing here renders markup or links from integration data.
const props = defineProps<{ detection: IntegrationDetection }>()

const { t, n, locale } = useI18n()

const timeText = computed(() => formatTime(props.detection.occurredAt, locale.value))

const rows = computed(() =>
  Object.entries(props.detection.payload).map(([name, value]) => ({ name, value: valueText(value) })),
)

function valueText(value: unknown): string {
  if (value === null || value === undefined) {
    return t('integration.notReported')
  }
  if (typeof value === 'number') {
    return n(value)
  }
  if (typeof value === 'object') {
    return JSON.stringify(value)
  }
  return String(value)
}

function formatTime(iso: string, language: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return iso
  }
  return new Intl.DateTimeFormat(language, { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}
</script>

<template>
  <div class="flex flex-col gap-3">
    <p class="text-body-medium break-words">{{ detection.summary }}</p>
    <dl class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-body-medium">
      <dt class="text-on-surface-variant">{{ t('integration.kind') }}</dt>
      <dd class="break-words">{{ detection.kind }}</dd>
      <dt class="text-on-surface-variant">{{ t('integration.server') }}</dt>
      <dd class="break-words">{{ detection.server }}</dd>
      <dt class="text-on-surface-variant">{{ t('integration.detectionTime') }}</dt>
      <dd>{{ timeText }}</dd>
      <template v-if="detection.severity">
        <dt class="text-on-surface-variant">{{ t('integration.reportedSeverity') }}</dt>
        <dd class="break-words">{{ detection.severity }}</dd>
      </template>
    </dl>
    <table class="w-full text-left text-body-medium">
      <caption class="sr-only">{{ t('integration.payload') }}</caption>
      <thead>
        <tr>
          <th scope="col" class="text-label-medium text-on-surface-variant">{{ t('integration.field') }}</th>
          <th scope="col" class="text-label-medium text-on-surface-variant">{{ t('integration.value') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in rows" :key="row.name">
          <th scope="row" class="break-all font-normal">{{ row.name }}</th>
          <td class="break-words">{{ row.value }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
