<script setup lang="ts">
import { useTemplateRef } from 'vue'
import { panelDomId, tabDomId, tabIndexAfterKey } from './tabs'

// Tabs over panels the owner renders (WAI-ARIA tabs pattern). One tab is selected and is the only one in the
// tab order (roving tabindex); arrow keys move and select, Home and End jump to the ends. A tab may carry a
// count (a badge) that is hidden at zero; its `badgeLabel` is read to screen readers, not shown.
// The owner keeps the panels mounted and hides the others, so switching loses no state.
export interface MdTab {
  id: string
  label: string
  badge?: number
  badgeLabel?: string
}

const props = defineProps<{ tabs: MdTab[]; modelValue: string; label: string; idPrefix: string }>()
const emit = defineEmits<{ 'update:modelValue': [id: string] }>()
const list = useTemplateRef<HTMLElement>('list')

function onKeydown(event: KeyboardEvent, index: number): void {
  const next = tabIndexAfterKey(event.key, index, props.tabs.length)
  if (next === null) return
  event.preventDefault()
  emit('update:modelValue', props.tabs[next]!.id)
  list.value?.querySelectorAll<HTMLElement>('[role="tab"]')[next]?.focus()
}
</script>

<template>
  <div ref="list" role="tablist" :aria-label="props.label" class="flex gap-1 overflow-x-auto border-b border-outline-variant" data-testid="tabs">
    <button
      v-for="(tab, index) in props.tabs"
      :id="tabDomId(props.idPrefix, tab.id)"
      :key="tab.id"
      type="button"
      role="tab"
      :aria-selected="tab.id === props.modelValue ? 'true' : 'false'"
      :aria-controls="panelDomId(props.idPrefix, tab.id)"
      :tabindex="tab.id === props.modelValue ? 0 : -1"
      :data-testid="`tab-${tab.id}`"
      class="md-state -mb-px inline-flex h-12 shrink-0 items-center gap-2 border-b-2 px-4 text-label-large whitespace-nowrap"
      :class="tab.id === props.modelValue ? 'border-primary text-primary' : 'border-transparent text-on-surface-variant'"
      @click="emit('update:modelValue', tab.id)"
      @keydown="onKeydown($event, index)"
    >
      {{ tab.label }}
      <span
        v-if="tab.badge"
        class="inline-flex h-5 min-w-5 items-center justify-center rounded-md-full bg-primary px-2 text-label-medium text-on-primary"
        :data-testid="`tab-badge-${tab.id}`"
        aria-hidden="true"
      >{{ tab.badge }}</span>
      <span v-if="tab.badge && tab.badgeLabel" class="sr-only">{{ tab.badgeLabel }}</span>
    </button>
  </div>
</template>
