<script setup lang="ts">
import { nextTick, onMounted, useId, useTemplateRef, watch } from 'vue'

const props = withDefaults(
  defineProps<{ label: string; supportingText?: string; indeterminate?: boolean; disabled?: boolean; hideLabel?: boolean }>(),
  { indeterminate: false, disabled: false, hideLabel: false },
)
const model = defineModel<boolean>({ default: false })

const inputId = useId()
const hintId = `${inputId}-hint`
const input = useTemplateRef<HTMLInputElement>('input')

// The mixed state exists only as a DOM property, not as an attribute. The browser clears it on every click, so it is
// set again after each change, from what the parent says the state is.
function syncIndeterminate(): void {
  if (input.value) input.value.indeterminate = props.indeterminate
}
onMounted(syncIndeterminate)
watch(() => props.indeterminate, syncIndeterminate)

async function onChange(event: Event): Promise<void> {
  model.value = (event.target as HTMLInputElement).checked
  await nextTick()
  syncIndeterminate()
}
</script>

<template>
  <div class="flex items-start gap-3">
    <!-- A 48px target around the 18px box; the native input keeps keyboard and screen reader behaviour. -->
    <span class="md-state relative grid h-12 w-12 shrink-0 place-items-center rounded-md-full text-primary has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-primary">
      <input
        ref="input"
        :id="inputId"
        :checked="model"
        type="checkbox"
        :disabled="props.disabled"
        :aria-describedby="supportingText ? hintId : undefined"
        class="h-[18px] w-[18px] cursor-pointer accent-primary disabled:cursor-not-allowed"
        @change="onChange"
      />
    </span>
    <!-- A hidden label still names the box for assistive technology; sr-only takes no room in the row. -->
    <label v-if="props.hideLabel" :for="inputId" class="sr-only">{{ label }}</label>
    <div v-else class="pt-2">
      <label :for="inputId" class="cursor-pointer text-body-large text-on-surface">{{ label }}</label>
      <p v-if="supportingText" :id="hintId" class="text-body-small text-on-surface-variant">{{ supportingText }}</p>
    </div>
  </div>
</template>
