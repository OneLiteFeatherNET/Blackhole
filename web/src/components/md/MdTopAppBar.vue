<script setup lang="ts">
import { RouterLink } from 'vue-router'

// MD3 top app bar: navigation button, product mark, a slot for the search field and the account actions.
defineProps<{ brand: string; brandLabel?: string }>()
</script>

<template>
  <header class="sticky top-0 z-30 flex h-16 items-center gap-2 bg-surface px-4 text-on-surface">
    <slot name="leading" />
    <RouterLink
      to="/"
      :aria-label="brandLabel ?? brand"
      class="flex shrink-0 items-center gap-2 rounded-md-full text-title-large text-on-surface outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
    >
      <svg viewBox="0 0 24 24" class="h-8 w-8 text-primary" aria-hidden="true">
        <circle cx="12" cy="12" r="10" fill="none" stroke="currentColor" stroke-width="2" />
        <circle cx="12" cy="12" r="5" fill="currentColor" />
      </svg>
      <span class="max-sm:sr-only">{{ brand }}</span>
    </RouterLink>
    <div class="flex min-w-0 flex-1 justify-center px-2">
      <slot name="search" />
    </div>
    <div class="flex shrink-0 items-center gap-2">
      <slot name="actions" />
    </div>
  </header>
</template>
