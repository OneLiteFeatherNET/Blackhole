<script setup lang="ts">
import { computed, useId } from 'vue'

const props = withDefaults(
  defineProps<{
    label: string
    type?: string
    required?: boolean
    disabled?: boolean
    error?: string
    supportingText?: string
    dense?: boolean
  }>(),
  { type: 'text', required: false, disabled: false, dense: false },
)
const model = defineModel<string>({ default: '' })

const inputId = useId()
const messageId = `${inputId}-message`
const message = computed(() => props.error || props.supportingText)
</script>

<template>
  <div class="flex flex-col gap-1">
    <div class="relative">
      <input
        :id="inputId"
        v-model="model"
        :type="props.type"
        :required="props.required"
        :disabled="props.disabled"
        :aria-invalid="props.error ? 'true' : undefined"
        :aria-describedby="message ? messageId : undefined"
        class="w-full rounded-md-xs border bg-transparent px-4 text-body-large text-on-surface outline-none disabled:opacity-[0.38] focus:border-2 focus:-outline-offset-2 focus:outline-2 focus:outline-primary"
        :class="[props.dense ? 'h-10' : 'h-14', props.error ? 'border-error border-2' : 'border-outline hover:border-on-surface']"
      />
      <label
        :for="inputId"
        class="pointer-events-none absolute top-0 left-3 -translate-y-1/2 bg-(--md-field-bg,var(--color-surface)) px-1 text-body-small"
        :class="props.error ? 'text-error' : 'text-on-surface-variant'"
      >
        {{ props.label }}<span v-if="props.required" aria-hidden="true"> *</span>
      </label>
    </div>
    <p
      v-if="message"
      :id="messageId"
      aria-live="polite"
      class="px-4 text-body-small"
      :class="props.error ? 'text-error' : 'text-on-surface-variant'"
    >
      {{ message }}
    </p>
  </div>
</template>
