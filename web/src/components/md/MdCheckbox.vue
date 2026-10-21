<script setup lang="ts">
import { useId } from 'vue'

defineProps<{ label: string; supportingText?: string }>()
const model = defineModel<boolean>({ default: false })

const inputId = useId()
const hintId = `${inputId}-hint`
</script>

<template>
  <div class="flex items-start gap-3">
    <!-- A 48px target around the 18px box; the native input keeps keyboard and screen reader behaviour. -->
    <span class="md-state relative grid h-10 w-10 shrink-0 place-items-center rounded-md-full text-primary has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-primary">
      <input
        :id="inputId"
        v-model="model"
        type="checkbox"
        :aria-describedby="supportingText ? hintId : undefined"
        class="h-[18px] w-[18px] cursor-pointer accent-primary"
      />
    </span>
    <div class="pt-2">
      <label :for="inputId" class="cursor-pointer text-body-large text-on-surface">{{ label }}</label>
      <p v-if="supportingText" :id="hintId" class="text-body-small text-on-surface-variant">{{ supportingText }}</p>
    </div>
  </div>
</template>
