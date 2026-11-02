<script setup lang="ts">
import { RouterLink } from 'vue-router'
import type { PillTone } from '../case/caseTones'
import StatusPill from '../StatusPill.vue'

// One row of the work queue. The whole row is one link to the entity (the stretched link covers the row), and
// the actions in the slot sit above it as buttons, so no button is ever inside a link.
defineProps<{
  href: string
  typeLabel: string
  icon: string
  id: string
  player?: string | null
  pills: Array<{ label: string; tone: PillTone }>
  age: string
}>()
</script>

<template>
  <li data-testid="row" class="relative flex flex-wrap items-center gap-x-4 gap-y-2 py-2">
    <RouterLink
      :to="href"
      class="flex min-h-12 min-w-0 flex-1 flex-wrap items-center gap-x-3 gap-y-1 rounded-md-sm outline-none after:absolute after:inset-0 focus-visible:outline-2 focus-visible:outline-primary"
    >
      <svg aria-hidden="true" viewBox="0 0 24 24" class="size-6 shrink-0 fill-on-surface-variant">
        <path :d="icon" />
      </svg>
      <span class="text-label-medium text-on-surface-variant">{{ typeLabel }}</span>
      <span class="text-title-small text-on-surface">{{ id }}</span>
      <span v-if="player" class="text-body-medium text-on-surface-variant">{{ player }}</span>
      <StatusPill v-for="pill in pills" :key="pill.label" :label="pill.label" :tone="pill.tone" />
      <span class="text-body-medium text-on-surface-variant">{{ age }}</span>
    </RouterLink>
    <div class="relative z-10 flex flex-wrap gap-2">
      <slot />
    </div>
  </li>
</template>
