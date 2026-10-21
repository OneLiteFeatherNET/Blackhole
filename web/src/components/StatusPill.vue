<script setup lang="ts">
// A read-only status label (not interactive, unlike MdChip). The tone is never the only signal:
// the text always says what the status is.
withDefaults(defineProps<{ label: string; tone?: 'neutral' | 'error' | 'primary' | 'tertiary' }>(), {
  tone: 'neutral',
})

const toneClasses = {
  neutral: 'bg-surface-container-highest text-on-surface-variant',
  error: 'bg-error-container text-on-error-container',
  primary: 'bg-primary-container text-on-primary-container',
  tertiary: 'bg-tertiary-container text-on-tertiary-container',
} as const
</script>

<template>
  <span
    class="inline-flex h-6 items-center rounded-md-sm px-2 text-label-medium whitespace-nowrap"
    :class="toneClasses[tone]"
  >
    {{ label }}
  </span>
</template>
