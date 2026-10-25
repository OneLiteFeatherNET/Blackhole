<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, type RouteLocationRaw } from 'vue-router'

// Variants (design section 2, "Buttons"): filled for the one primary action of a section, tonal for the secondary
// action and a row's primary action, outlined for a neutral choice, text for cancel and inline help, error for the
// confirm of a destructive action only. `as="link"` renders a RouterLink with the same classes, so a navigation is a
// real link that looks like a tonal button.
const props = withDefaults(
  defineProps<{
    variant?: 'filled' | 'tonal' | 'outlined' | 'text' | 'error'
    type?: 'button' | 'submit' | 'reset'
    disabled?: boolean
    as?: 'button' | 'link'
    to?: RouteLocationRaw
  }>(),
  { variant: 'filled', type: 'button', disabled: false, as: 'button', to: undefined },
)

const variantClasses = {
  filled: 'bg-primary text-on-primary',
  tonal: 'bg-secondary-container text-on-secondary-container',
  outlined: 'border border-outline text-primary',
  text: 'text-primary',
  error: 'bg-error text-on-error',
} as const

const classes = computed(() => [
  'md-state inline-flex h-10 min-w-16 cursor-pointer items-center justify-center gap-2 rounded-md-full px-6 text-control whitespace-nowrap disabled:pointer-events-none disabled:opacity-(--md-sys-state-disabled-opacity)',
  variantClasses[props.variant],
])
</script>

<template>
  <RouterLink v-if="props.as === 'link' && props.to !== undefined" :to="props.to" :class="classes">
    <slot />
  </RouterLink>
  <button v-else :type="props.type" :disabled="props.disabled" :class="classes">
    <slot />
  </button>
</template>
