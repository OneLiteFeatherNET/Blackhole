<script setup lang="ts">
// Icon button. `label` is both the accessible name and the tooltip (title), since an icon alone says nothing to a screen reader.
defineProps<{ label: string; path: string }>()
</script>

<template>
  <button
    type="button"
    :aria-label="label"
    :title="label"
    class="md-state grid h-10 w-10 shrink-0 place-items-center rounded-md-full text-on-surface-variant disabled:opacity-[0.38] disabled:pointer-events-none"
  >
    <svg viewBox="0 0 24 24" class="h-6 w-6" aria-hidden="true"><path :d="path" fill="currentColor" /></svg>
  </button>
</template>
