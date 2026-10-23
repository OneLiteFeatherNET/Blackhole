<script setup lang="ts">
// Filter chip. Pass `selected` to make it a toggle (exposed as aria-pressed); omit it for an action chip.
// The explicit undefined default stops Vue from casting an absent Boolean prop to false.
withDefaults(defineProps<{ label: string; selected?: boolean }>(), { selected: undefined })
</script>

<template>
  <button
    type="button"
    :aria-pressed="selected === undefined ? undefined : selected ? 'true' : 'false'"
    class="md-state inline-flex h-8 items-center gap-2 rounded-md-sm border px-3 text-label-large whitespace-nowrap md-colors"
    :class="
      selected
        ? 'border-transparent bg-secondary-container text-on-secondary-container'
        : 'border-outline text-on-surface-variant'
    "
  >
    {{ label }}
  </button>
</template>
