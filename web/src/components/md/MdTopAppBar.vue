<script setup lang="ts">
defineProps<{ title: string }>()
</script>

<template>
  <header class="flex min-h-16 flex-wrap items-center gap-2 bg-surface px-4 py-2 text-on-surface">
    <h1 class="min-w-0 flex-1 truncate text-title-large">{{ title }}</h1>
    <div class="flex flex-wrap items-center gap-2">
      <slot name="actions" />
    </div>
  </header>
</template>
