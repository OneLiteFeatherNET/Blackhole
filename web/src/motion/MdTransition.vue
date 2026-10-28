<script setup lang="ts">
// A <Transition> with an MD3 motion pattern (classes in styles/main.css). Under "reduce motion"
// every pattern becomes the plain opacity fade, so nothing moves or scales.
import { computed } from 'vue'
import { useReducedMotion } from './useReducedMotion'

const props = withDefaults(
  defineProps<{
    name: 'fade' | 'fade-through' | 'scale' | 'menu' | 'snackbar' | 'badge' | 'modal' | 'side-sheet'
    mode?: 'in-out' | 'out-in'
    appear?: boolean
  }>(),
  { mode: undefined, appear: false },
)

const reduced = useReducedMotion()
const resolved = computed(() => (reduced.value ? 'md-fade' : `md-${props.name}`))
</script>

<template>
  <Transition :name="resolved" :mode="props.mode" :appear="props.appear">
    <slot />
  </Transition>
</template>
