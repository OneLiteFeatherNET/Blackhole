<script setup lang="ts">
import { computed } from 'vue'
import { INBOX_ICON, LOCK_ICON, SEARCH_ICON } from '../icons'

// Design section 2, "States": an icon, a title, one sentence and one next step (the default slot).
// `no-items`: nothing exists yet, the slot holds the create action. `no-matches`: filters hide everything, the slot
// holds "Clear filters". `no-access`: the viewer may not see this, the slot holds the reason or the way to ask.
const props = defineProps<{
  variant: 'no-items' | 'no-matches' | 'no-access'
  title: string
  description?: string
}>()

const ICONS = {
  'no-items': INBOX_ICON,
  'no-matches': SEARCH_ICON,
  'no-access': LOCK_ICON,
} as const

const icon = computed(() => ICONS[props.variant])
</script>

<template>
  <div :data-variant="props.variant" class="flex flex-col items-center gap-2 px-4 py-8 text-center">
    <svg aria-hidden="true" viewBox="0 0 24 24" class="size-12 fill-current text-on-surface-variant">
      <path :d="icon" />
    </svg>
    <h3 class="text-card text-on-surface">{{ props.title }}</h3>
    <p v-if="props.description" class="text-body-medium text-on-surface-variant">{{ props.description }}</p>
    <div v-if="$slots.default" class="pt-2">
      <slot />
    </div>
  </div>
</template>
