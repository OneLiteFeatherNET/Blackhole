<script setup lang="ts">
// The navigation bar of a compact window: at most five destinations along the bottom edge, the last one a More button
// that opens the modal rail with every other destination (see NavigationRail).
import { RouterLink, useRoute } from 'vue-router'
import { MORE_ICON } from '../../icons'
import MdTransition from '../../motion/MdTransition.vue'

export interface BarItem {
  to: string
  label: string
  icon: string
  badge?: { text: string; label: string }
}

defineProps<{
  label: string
  items: BarItem[]
  more: { label: string; expanded: boolean }
}>()
const emit = defineEmits<{ more: [] }>()

const route = useRoute()

// Same rule as the rail: the entry is current on its own page or on a detail page that names it in `meta.nav`.
function isCurrent(to: string): boolean {
  return route.path === to || route.meta.nav === to
}
</script>

<template>
  <nav
    data-testid="nav-bar"
    :aria-label="label"
    class="fixed inset-x-0 bottom-0 z-30 flex h-20 items-stretch justify-around gap-1 bg-surface-container px-2 pb-2 text-on-surface shadow-md-2"
  >
    <RouterLink v-for="item in items" :key="item.to" v-slot="{ href, navigate }" :to="item.to" custom>
      <a
        :href="href"
        :aria-current="isCurrent(item.to) ? 'page' : undefined"
        class="group flex min-h-12 min-w-0 flex-1 flex-col items-center justify-center gap-1 rounded-md-md text-label-medium focus-ring"
        :class="isCurrent(item.to) ? 'text-on-surface' : 'text-on-surface-variant'"
        @click="navigate"
      >
        <span
          class="md-state md-colors relative grid h-8 w-14 place-items-center rounded-md-full"
          :class="isCurrent(item.to) ? 'bg-secondary-container text-on-secondary-container' : ''"
        >
          <svg viewBox="0 0 24 24" class="h-6 w-6" aria-hidden="true"><path :d="item.icon" fill="currentColor" /></svg>
          <MdTransition name="badge" mode="out-in">
            <span
              v-if="item.badge"
              :key="item.badge.text"
              data-testid="nav-badge"
              aria-hidden="true"
              class="absolute -top-1 right-0 grid h-4 min-w-4 place-items-center rounded-md-full bg-error px-1 text-label-medium text-on-error"
            >
              {{ item.badge.text }}
            </span>
          </MdTransition>
        </span>
        <span class="max-w-full truncate">{{ item.label }}</span>
        <span v-if="item.badge" class="sr-only">{{ item.badge.label }}</span>
      </a>
    </RouterLink>
    <button
      type="button"
      data-testid="nav-more"
      aria-haspopup="dialog"
      :aria-expanded="more.expanded"
      class="md-state md-colors group flex min-h-12 min-w-0 flex-1 flex-col items-center justify-center gap-1 rounded-md-md text-label-medium text-on-surface-variant focus-ring"
      @click="emit('more')"
    >
      <span class="grid h-8 w-14 place-items-center rounded-md-full" :class="more.expanded ? 'bg-secondary-container text-on-secondary-container' : ''">
        <svg viewBox="0 0 24 24" class="h-6 w-6" aria-hidden="true"><path :d="MORE_ICON" fill="currentColor" /></svg>
      </span>
      <span class="max-w-full truncate">{{ more.label }}</span>
    </button>
  </nav>
</template>
