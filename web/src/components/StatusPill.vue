<script setup lang="ts">
// A read-only status label (not interactive, unlike MdChip). The tone is never the only signal:
// the text always says what the status is. Each tone is one MD3 role pair; the case tones are mapped in
// components/case/caseTones.ts.
withDefaults(
  defineProps<{
    label: string
    tone?: 'neutral' | 'outline' | 'error' | 'primary' | 'secondary' | 'tertiary' | 'success' | 'warning'
  }>(),
  { tone: 'neutral' },
)

const toneClasses = {
  neutral: 'bg-surface-container-highest text-on-surface-variant',
  outline: 'border border-outline text-on-surface-variant',
  error: 'bg-error-container text-on-error-container',
  primary: 'bg-primary-container text-on-primary-container',
  secondary: 'bg-secondary-container text-on-secondary-container',
  tertiary: 'bg-tertiary-container text-on-tertiary-container',
  success: 'bg-success-container text-on-success-container',
  warning: 'bg-warning-container text-on-warning-container',
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
