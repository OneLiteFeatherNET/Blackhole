<script setup lang="ts">
import { useId } from 'vue'

defineProps<{
  label: string
  options: { value: string; label: string }[]
}>()
const model = defineModel<string>({ default: '' })

const selectId = useId()
</script>

<template>
  <div class="relative flex flex-col">
    <select
      :id="selectId"
      v-model="model"
      class="h-14 w-full cursor-pointer appearance-none rounded-md-xs border border-outline bg-transparent px-4 pr-10 text-body-large text-on-surface outline-none hover:border-on-surface focus:border-2 focus:-outline-offset-2 focus:outline-2 focus:outline-primary"
    >
      <option v-for="option in options" :key="option.value" :value="option.value">
        {{ option.label }}
      </option>
    </select>
    <label
      :for="selectId"
      class="pointer-events-none absolute top-0 left-3 -translate-y-1/2 bg-surface px-1 text-body-small text-on-surface-variant"
    >
      {{ label }}
    </label>
    <svg
      viewBox="0 0 24 24"
      class="pointer-events-none absolute top-1/2 right-3 h-6 w-6 -translate-y-1/2 text-on-surface-variant"
      aria-hidden="true"
    >
      <path d="M7 10l5 5 5-5z" fill="currentColor" />
    </svg>
  </div>
</template>
