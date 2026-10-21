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
      class="h-full rounded-md-full bg-primary transition-[width] duration-300"
      :style="{ width: `${current}%` }"
    />
    <div v-else class="md-indeterminate absolute inset-y-0 w-1/3 rounded-md-full bg-primary" />
  </div>
</template>

<style scoped>
.md-indeterminate {
  animation: md-indeterminate 1.6s ease-in-out infinite;
}

@keyframes md-indeterminate {
  from {
    left: -35%;
  }
  to {
    left: 100%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .md-indeterminate {
    animation-duration: 4s;
  }
}
</style>
