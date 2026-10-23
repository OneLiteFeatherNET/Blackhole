<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { scaleOf, scoreZone } from './scoreScale'

const props = defineProps<{
  label: string
  score: number
  baseScore: number
  softThreshold?: number | null
  permanentThreshold: number
}>()

const { t } = useI18n()

const scale = computed(() => scaleOf(props.baseScore, props.permanentThreshold, props.score))
const zone = computed(() => scoreZone(props.score, props.softThreshold, props.permanentThreshold))
const hasSoft = computed(() => props.softThreshold !== null && props.softThreshold !== undefined)

const fillClass = computed(
  () => ({ healthy: 'bg-primary', soft: 'bg-tertiary', permanent: 'bg-error' })[zone.value],
)
const valueText = computed(() =>
  t(hasSoft.value ? 'scoreBar.valueTextSoft' : 'scoreBar.valueText', {
    score: props.score,
    zone: t(`scoreBar.zone.${zone.value}`),
    soft: props.softThreshold,
    permanent: props.permanentThreshold,
  }),
)
</script>

<template>
  <div>
    <div class="relative pt-1 pb-3">
      <div
        role="meter"
        :aria-label="label"
        :aria-valuemin="scale.min"
        :aria-valuemax="scale.max"
        :aria-valuenow="score"
        :aria-valuetext="valueText"
        :data-zone="zone"
        class="h-3 overflow-hidden rounded-md-full bg-surface-container-highest"
      >
        <div class="h-full rounded-md-full md-width" :class="fillClass" :style="{ width: `${scale.percent(score)}%` }" />
      </div>
      <!-- Threshold ticks; the numbers are repeated as text below, so the ticks are decorative. -->
      <span
        v-if="hasSoft"
        data-testid="marker-soft"
        aria-hidden="true"
        class="absolute top-0 h-5 w-0.5 bg-on-surface-variant"
        :style="{ left: `${scale.percent(softThreshold as number)}%` }"
      />
      <span
        data-testid="marker-permanent"
        aria-hidden="true"
        class="absolute top-0 h-5 w-0.5 bg-error"
        :style="{ left: `${scale.percent(permanentThreshold)}%` }"
      />
    </div>
    <p class="flex flex-wrap justify-between gap-x-4 text-body-small text-on-surface-variant">
      <span data-testid="zone" class="font-medium text-on-surface first-letter:uppercase">{{ t(`scoreBar.zone.${zone}`) }}</span>
      <span v-if="hasSoft">{{ t('scoreBar.soft', { value: softThreshold }) }}</span>
      <span>{{ t('scoreBar.permanent', { value: permanentThreshold }) }}</span>
    </p>
  </div>
</template>
