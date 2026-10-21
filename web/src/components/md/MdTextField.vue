<script setup lang="ts">
import { computed, useId } from 'vue'

const props = withDefaults(
  defineProps<{
    label: string
    type?: string
    required?: boolean
    error?: string
    supportingText?: string
  }>(),
  { type: 'text', required: false },
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
        :aria-invalid="props.error ? 'true' : undefined"
        :aria-describedby="message ? messageId : undefined"
        class="h-14 w-full rounded-md-xs border bg-transparent px-4 text-body-large text-on-surface outline-none focus:border-2 focus:-outline-offset-2 focus:outline-2 focus:outline-primary"
        :class="props.error ? 'border-error border-2' : 'border-outline hover:border-on-surface'"
      />
      <label
        :for="inputId"
        class="pointer-events-none absolute top-0 left-3 -translate-y-1/2 bg-surface px-1 text-body-small"
        :class="props.error ? 'text-error' : 'text-on-surface-variant'"
      >
        {{ props.label }}<span v-if="props.required" aria-hidden="true"> *</span>
      </label>
    </div>
    <p
      v-if="message"
      :id="messageId"
      class="px-4 text-body-small"
      :class="props.error ? 'text-error' : 'text-on-surface-variant'"
    >
      {{ message }}
    </p>
  </div>
</template>
