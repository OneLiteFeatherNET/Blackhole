<script setup lang="ts">
import { onBeforeUnmount, watch } from 'vue'

const props = defineProps<{ open: boolean; message: string; actionLabel?: string }>()
const emit = defineEmits<{ action: []; close: [] }>()

const AUTO_CLOSE_MS = 6000
let timer: ReturnType<typeof setTimeout> | undefined

// The snackbar hides itself after a while; the parent clears `open` on the close event.
watch(
  () => props.open,
  (open) => {
    clearTimeout(timer)
    if (open) timer = setTimeout(() => emit('close'), AUTO_CLOSE_MS)
  },
  { immediate: true },
)
onBeforeUnmount(() => clearTimeout(timer))
</script>

<template>
  <!-- The live region stays mounted so screen readers announce the message when it appears. -->
  <div role="status" aria-live="polite" class="pointer-events-none fixed inset-x-4 bottom-4 z-50 mx-auto max-w-md">
    <div
      v-if="props.open"
      class="pointer-events-auto flex items-center gap-3 rounded-md-xs bg-inverse-surface py-3 pr-2 pl-4 text-body-medium text-inverse-on-surface shadow-md-3"
    >
      <span class="min-w-0 flex-1">{{ props.message }}</span>
      <button
        v-if="props.actionLabel"
        type="button"
        class="md-state rounded-md-full px-3 py-2 text-label-large text-inverse-primary"
        @click="emit('action')"
      >
        {{ props.actionLabel }}
      </button>
    </div>
  </div>
</template>
