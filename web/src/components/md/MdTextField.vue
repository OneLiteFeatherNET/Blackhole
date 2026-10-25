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
    /** A textarea of `rows` lines instead of a single-line input. */
    multiline?: boolean
    rows?: number
  }>(),
  { type: 'text', required: false, disabled: false, dense: false, multiline: false, rows: 4 },
)

const fieldClass =
  'w-full rounded-md-xs border bg-transparent text-body-large text-on-surface outline-none disabled:opacity-(--md-sys-state-disabled-opacity) focus:border-2 focus:-outline-offset-2 focus:outline-2 focus:outline-primary'
const model = defineModel<string>({ default: '' })

const inputId = useId()
const messageId = `${inputId}-message`
const message = computed(() => props.error || props.supportingText)
</script>

<template>
  <div class="flex flex-col gap-1">
    <div class="relative">
      <textarea
        v-if="props.multiline"
        :id="inputId"
        v-model="model"
        :rows="props.rows"
        :required="props.required"
        :disabled="props.disabled"
        :aria-invalid="props.error ? 'true' : undefined"
        :aria-describedby="message ? messageId : undefined"
        class="block px-4 py-3"
        :class="[fieldClass, props.error ? 'border-error border-2' : 'border-outline hover:border-on-surface']"
      />
      <input
        v-else
        :id="inputId"
        v-model="model"
        :type="props.type"
        :required="props.required"
        :disabled="props.disabled"
        :aria-invalid="props.error ? 'true' : undefined"
        :aria-describedby="message ? messageId : undefined"
        :class="[
          fieldClass,
          props.dense ? 'h-10 px-3' : 'h-14 px-4',
          props.error ? 'border-error border-2' : 'border-outline hover:border-on-surface',
        ]"
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
