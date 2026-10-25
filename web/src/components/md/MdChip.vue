<script setup lang="ts">
import { CHECK_ICON, CLOSE_ICON } from '../../icons'

// Three chips (design section 2, "Chips"). A clickable chip is a filter chip; a chip that is not clickable is a
// status chip; an input chip shows an applied filter and removes it. All are fully rounded.
//  - filter: a toggle. Pass `selected` to expose aria-pressed; omit it for an action chip. Selected shows a check.
//  - input: an applied filter with a remove button named "Remove filter <label>"; emits `remove`.
//  - status: a plain, non-interactive label (case, appeal or report status).
// The explicit undefined default stops Vue from casting an absent Boolean prop to false.
const props = withDefaults(defineProps<{ label: string; selected?: boolean; variant?: 'filter' | 'input' | 'status' }>(), {
  selected: undefined,
  variant: 'filter',
})
const emit = defineEmits<{ remove: [] }>()
</script>

<template>
  <span
    v-if="props.variant === 'status'"
    class="inline-flex h-6 items-center gap-1 rounded-full bg-surface-container-highest px-3 text-label-large text-on-surface-variant whitespace-nowrap"
  >
    {{ props.label }}
  </span>

  <span
    v-else-if="props.variant === 'input'"
    class="inline-flex h-8 items-center gap-1 rounded-full border border-outline pr-1 pl-3 text-label-large text-on-surface-variant whitespace-nowrap"
  >
    {{ props.label }}
    <button
      type="button"
      :aria-label="`Remove filter ${props.label}`"
      class="md-state inline-flex size-6 items-center justify-center rounded-full"
      @click="emit('remove')"
    >
      <svg aria-hidden="true" viewBox="0 0 24 24" class="size-4 fill-current"><path :d="CLOSE_ICON" /></svg>
    </button>
  </span>

  <button
    v-else
    type="button"
    :aria-pressed="props.selected === undefined ? undefined : props.selected ? 'true' : 'false'"
    class="md-state inline-flex h-8 items-center gap-2 rounded-full border px-3 text-label-large whitespace-nowrap md-colors"
    :class="
      props.selected
        ? 'border-transparent bg-secondary-container text-on-secondary-container'
        : 'border-outline text-on-surface-variant'
    "
  >
    <svg v-if="props.selected" aria-hidden="true" viewBox="0 0 24 24" class="size-4 fill-current"><path :d="CHECK_ICON" /></svg>
    {{ props.label }}
  </button>
</template>
