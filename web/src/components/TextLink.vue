<script setup lang="ts">
import { RouterLink, type RouteLocationRaw } from 'vue-router'

// The one text link (audit 2, "Text links"): a route link when `to` is given, a plain anchor for `href`.
// The focus ring is kept for keyboard users.
const props = defineProps<{ to?: RouteLocationRaw; href?: string }>()

const classes =
  'text-primary underline underline-offset-2 outline-none focus-visible:outline-2 focus-visible:outline-primary'
</script>

<template>
  <RouterLink v-if="props.to !== undefined" :to="props.to" :class="classes">
    <slot />
  </RouterLink>
  <a v-else :href="props.href" :class="classes">
    <slot />
  </a>
</template>
