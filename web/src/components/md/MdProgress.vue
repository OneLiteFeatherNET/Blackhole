<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ label: string; value?: number }>()

// Without a value the bar is indeterminate and carries no aria-valuenow.
const current = computed(() =>
  props.value === undefined ? undefined : Math.min(100, Math.max(0, props.value)),
)
</script>

<template>
  <div
    role="progressbar"
    :aria-label="props.label"
    aria-valuemin="0"
    aria-valuemax="100"
    :aria-valuenow="current"
    class="relative h-1 w-full overflow-hidden rounded-md-full bg-surface-container-highest"
  >
    <div
      v-if="current !== undefined"
      class="h-full rounded-md-full bg-primary md-width"
      :style="{ width: `${current}%` }"
    />
    <div v-else class="md-indeterminate absolute inset-y-0 w-1/3 rounded-md-full bg-primary" />
  </div>
</template>

<style scoped>
/* Slides the bar across with a transform; reduced motion pulses its opacity instead. */
.md-indeterminate {
  left: 0;
  animation: md-indeterminate calc(var(--md-sys-motion-duration-long2) * 3) var(--md-sys-motion-easing-standard) infinite;
}

@keyframes md-indeterminate {
  from {
    transform: translateX(-100%);
  }
  to {
    transform: translateX(300%);
  }
}

@keyframes md-indeterminate-reduced {
  50% {
    opacity: 0.4;
  }
}

@media (prefers-reduced-motion: reduce) {
  .md-indeterminate {
    transform: none;
    animation-name: md-indeterminate-reduced;
    animation-duration: calc(var(--md-sys-motion-duration-long2) * 4);
  }
}
</style>
