<script lang="ts">
// The option of a segmented button. `testid` names the segment in tests.
export interface SegmentOption<T extends string> {
  value: T
  label: string
  testid?: string
}
</script>

<script setup lang="ts" generic="T extends string">
// A segmented button (design section 2, "Segmented buttons"): a connected row where exactly one option is chosen.
// The chosen segment is filled with the secondary container and shows a check. Each segment is a native button with
// aria-pressed, so Tab, Enter and Space work as for any button. Left and Right (and Home and End) move the focus
// between segments; the choice changes only on activation.
import { ref } from 'vue'
import { CHECK_ICON } from '../../icons'

const props = defineProps<{ modelValue: T; options: SegmentOption<T>[]; label: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: T] }>()

const group = ref<HTMLElement | null>(null)

function moveFocus(event: KeyboardEvent, index: number): void {
  const count = props.options.length
  const target = {
    ArrowRight: (index + 1) % count,
    ArrowLeft: (index - 1 + count) % count,
    Home: 0,
    End: count - 1,
  }[event.key]
  if (target === undefined) return
  event.preventDefault()
  group.value?.querySelectorAll<HTMLButtonElement>('button')[target]?.focus()
}
</script>

<template>
  <div ref="group" role="group" :aria-label="props.label" class="inline-flex overflow-hidden rounded-md-full border border-outline">
    <button
      v-for="(option, index) in props.options"
      :key="option.value"
      type="button"
      :aria-pressed="option.value === props.modelValue ? 'true' : 'false'"
      :data-testid="option.testid"
      class="md-state inline-flex h-10 items-center gap-2 px-4 text-label-large whitespace-nowrap md-colors"
      :class="[
        index > 0 ? 'border-l border-outline' : '',
        option.value === props.modelValue ? 'bg-secondary-container text-on-secondary-container' : 'text-on-surface',
      ]"
      @click="emit('update:modelValue', option.value)"
      @keydown="moveFocus($event, index)"
    >
      <svg v-if="option.value === props.modelValue" aria-hidden="true" viewBox="0 0 24 24" class="size-4 fill-current"><path :d="CHECK_ICON" /></svg>
      {{ option.label }}
    </button>
  </div>
</template>
