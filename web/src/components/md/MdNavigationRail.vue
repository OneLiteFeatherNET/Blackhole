<script setup lang="ts">
import { RouterLink } from 'vue-router'

const props = defineProps<{
  label: string
  items: { to: string; label: string; icon: string }[]
}>()
</script>

<template>
  <nav :aria-label="props.label" class="flex w-20 shrink-0 flex-col items-center gap-2 bg-surface py-3">
    <RouterLink v-for="item in props.items" :key="item.to" v-slot="{ href, navigate, isExactActive }" :to="item.to" custom>
      <a
        :href="href"
        :aria-current="isExactActive ? 'page' : undefined"
        class="group flex w-full flex-col items-center gap-1 rounded-md-md py-1 text-label-medium text-on-surface outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
        @click="navigate"
      >
        <span
          class="md-state grid h-8 w-14 place-items-center rounded-md-full transition-colors group-hover:before:opacity-[0.08] group-focus-visible:before:opacity-[0.12]"
          :class="isExactActive ? 'bg-secondary-container text-on-secondary-container' : 'text-on-surface-variant'"
        >
          <svg viewBox="0 0 24 24" class="h-6 w-6" aria-hidden="true">
            <path :d="item.icon" fill="currentColor" />
          </svg>
        </span>
        {{ item.label }}
      </a>
    </RouterLink>
  </nav>
</template>
