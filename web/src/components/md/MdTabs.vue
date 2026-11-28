<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, useTemplateRef, watch } from 'vue'
import { panelDomId, revealScrollLeft, tabDomId, tabEdges, tabIndexAfterKey, type TabEdges } from './tabs'

// Tabs over panels the owner renders (WAI-ARIA tabs pattern). One tab is selected and is the only one in the
// tab order (roving tabindex); arrow keys move and select, Home and End jump to the ends. A tab may carry a
// count (a badge) that is hidden at zero; its `badgeLabel` is read to screen readers, not shown.
// The owner keeps the panels mounted and hides the others, so switching loses no state.
// On a narrow screen the strip scrolls sideways without a scrollbar: a fade marks the edge with hidden tabs,
// and the selected tab is scrolled into view.
export interface MdTab {
  id: string
  label: string
  badge?: number
  badgeLabel?: string
}

const props = defineProps<{ tabs: MdTab[]; modelValue: string; label: string; idPrefix: string }>()
const emit = defineEmits<{ 'update:modelValue': [id: string] }>()
const list = useTemplateRef<HTMLElement>('list')

const edges = ref<TabEdges>({ start: false, end: false })
const fade = computed(() => {
  if (edges.value.start && edges.value.end) return 'both'
  if (edges.value.start) return 'start'
  return edges.value.end ? 'end' : 'none'
})

function updateEdges(): void {
  const strip = list.value
  if (strip) edges.value = tabEdges(strip.scrollLeft, strip.scrollWidth, strip.clientWidth)
}

function prefersReducedMotion(): boolean {
  return typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches
}

/** Scrolls the strip just far enough for the selected tab to be fully visible. */
function revealSelected(): void {
  const strip = list.value
  const index = props.tabs.findIndex((tab) => tab.id === props.modelValue)
  const button = strip?.querySelectorAll<HTMLElement>('[role="tab"]')[index]
  if (!strip || !button) return
  const tab = button.getBoundingClientRect()
  const start = tab.left - strip.getBoundingClientRect().left + strip.scrollLeft
  const left = revealScrollLeft({ start, end: start + tab.width }, { left: strip.scrollLeft, width: strip.clientWidth })
  if (left !== strip.scrollLeft) strip.scrollTo({ left, behavior: prefersReducedMotion() ? 'auto' : 'smooth' })
  updateEdges()
}

watch(() => props.modelValue, revealSelected, { flush: 'post' })
watch(() => props.tabs, updateEdges, { deep: true, flush: 'post' })
onMounted(() => {
  revealSelected()
  window.addEventListener('resize', updateEdges)
})
onBeforeUnmount(() => window.removeEventListener('resize', updateEdges))

function onKeydown(event: KeyboardEvent, index: number): void {
  const next = tabIndexAfterKey(event.key, index, props.tabs.length)
  if (next === null) return
  event.preventDefault()
  emit('update:modelValue', props.tabs[next]!.id)
  list.value?.querySelectorAll<HTMLElement>('[role="tab"]')[next]?.focus()
}
</script>

<template>
  <!-- The divider is an inset shadow, not a border: a negative margin on the tabs would overflow the strip by 1 px and show a scrollbar. -->
  <div
    ref="list"
    role="tablist"
    :aria-label="props.label"
    class="no-scrollbar flex gap-1 overflow-x-auto shadow-[inset_0_-1px_0_0_var(--md-sys-color-outline-variant)]"
    :data-fade="fade"
    data-testid="tabs"
    @scroll.passive="updateEdges"
  >
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
      class="md-state inline-flex h-12 shrink-0 items-center gap-2 border-b-2 px-4 text-label-large whitespace-nowrap"
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
