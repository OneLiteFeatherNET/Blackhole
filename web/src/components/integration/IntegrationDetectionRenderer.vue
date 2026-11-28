<script setup lang="ts">
import { computed, defineAsyncComponent, type Component } from 'vue'
import { useI18n } from 'vue-i18n'
import GenericDetectionTable from './GenericDetectionTable.vue'
import { renderers, resolveRenderer, type RendererLoader } from './registry'
import { rendererKey, type IntegrationDetection } from './types'

// Picks the reviewed component for a detection, or the generic table. The trust line is shown in every case: who
// reported it, and that it is a signal and staff decide.
const props = withDefaults(
  defineProps<{ detection: IntegrationDetection; table?: Readonly<Record<string, RendererLoader>> }>(),
  { table: () => renderers },
)

const { t } = useI18n()

const specific = computed<Component | null>(() => {
  const loader = resolveRenderer(rendererKey(props.detection), props.table)
  return loader ? defineAsyncComponent(loader) : null
})
</script>

<template>
  <section class="flex flex-col gap-3" :aria-label="t('integration.title')">
    <header class="flex flex-col gap-1">
      <p class="text-label-medium text-on-surface-variant">{{ t('integration.reportedBy', { source: detection.sourceSystem }) }}</p>
      <p class="text-body-medium text-on-surface">{{ t('integration.signalNotVerdict') }}</p>
    </header>
    <component :is="specific" v-if="specific" :detection="detection" />
    <GenericDetectionTable v-else :detection="detection" />
  </section>
</template>
