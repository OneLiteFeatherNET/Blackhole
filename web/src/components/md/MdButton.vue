<script setup lang="ts">
const props = withDefaults(
  defineProps<{
    variant?: 'filled' | 'tonal' | 'outlined' | 'text'
    type?: 'button' | 'submit' | 'reset'
    disabled?: boolean
  }>(),
  { variant: 'filled', type: 'button', disabled: false },
)

const variantClasses = {
  filled: 'bg-primary text-on-primary',
  tonal: 'bg-secondary-container text-on-secondary-container',
  outlined: 'border border-outline text-primary',
  text: 'text-primary',
} as const
</script>

<template>
  <button
    :type="props.type"
    :disabled="props.disabled"
    class="md-state inline-flex h-10 min-w-16 cursor-pointer items-center justify-center gap-2 rounded-md-full px-6 text-label-large whitespace-nowrap disabled:pointer-events-none disabled:opacity-[0.38]"
    :class="variantClasses[props.variant]"
  >
    <slot />
  </button>
</template>
