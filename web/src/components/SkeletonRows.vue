<script setup lang="ts">
// Placeholder rows for a table on its first load (design section 2, "Loading"). Decorative: the container that shows
// them carries aria-busy, and the rows hold no text.
const props = withDefaults(defineProps<{ rows?: number; columns?: number }>(), { rows: 3, columns: 3 })
</script>

<template>
  <div aria-hidden="true" class="flex flex-col">
    <div
      v-for="row in props.rows"
      :key="row"
      data-skeleton="row"
      class="flex items-center gap-4 border-t border-outline-variant px-4 py-3"
    >
      <span
        v-for="column in props.columns"
        :key="column"
        data-skeleton="cell"
        class="h-3 flex-1 rounded-md-xs bg-surface-container-highest"
      />
    </div>
  </div>
</template>
