<script setup lang="ts">
const props = withDefaults(defineProps<{ variant?: 'filled' | 'elevated' | 'outlined' }>(), {
  variant: 'filled',
})

const variantClasses = {
  filled: 'bg-surface-container-highest',
  elevated: 'bg-surface-container-low shadow-md-1',
  outlined: 'border border-outline-variant bg-surface',
} as const
</script>

<template>
  <div class="rounded-md-md p-4 text-on-surface" :class="variantClasses[props.variant]">
    <slot />
  </div>
</template>
