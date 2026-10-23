<script setup lang="ts">
import { nextTick, ref, useId, watch } from 'vue'
import MdTransition from '../../motion/MdTransition.vue'

const props = defineProps<{ open: boolean; headline: string; wide?: boolean }>()
const emit = defineEmits<{ close: [] }>()

const titleId = useId()
const panel = ref<HTMLElement | null>(null)
let returnFocusTo: HTMLElement | null = null

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

// Move focus into the dialog on open and hand it back to the opener on close.
watch(
  () => props.open,
  async (open) => {
    if (open) {
      returnFocusTo = document.activeElement instanceof HTMLElement ? document.activeElement : null
      await nextTick()
      panel.value?.focus()
    } else {
      returnFocusTo?.focus()
      returnFocusTo = null
    }
  },
  // A dialog that is mounted already open (e.g. behind a v-if) must take focus as well.
  { immediate: true },
)

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    // A dialog opened from inside another dialog sits in its DOM; its Escape must not close the outer one too.
    event.stopPropagation()
    emit('close')
    return
  }
  if (event.key !== 'Tab' || !panel.value) return
  event.stopPropagation()

  // Keep Tab within the dialog.
  const items = Array.from(panel.value.querySelectorAll<HTMLElement>(FOCUSABLE))
  if (items.length === 0) {
    event.preventDefault()
    return
  }
  const first = items[0]!
  const last = items[items.length - 1]!
  const active = document.activeElement
  if (event.shiftKey && (active === first || active === panel.value)) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && active === last) {
    event.preventDefault()
    first.focus()
  }
}
</script>

<template>
  <MdTransition name="scale">
    <div
      v-if="props.open"
      class="fixed inset-0 z-50 flex items-center justify-center p-4"
      :class="{ 'max-sm:p-0': props.wide }"
    >
      <div class="absolute inset-0 bg-scrim/[0.32]" @click="emit('close')" />
      <div
        ref="panel"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="titleId"
        tabindex="-1"
        style="--md-field-bg: var(--color-surface-container-high)"
        class="relative flex max-h-[90dvh] w-full flex-col overflow-y-auto rounded-md-xl bg-surface-container-high p-6 text-on-surface shadow-md-3 outline-none"
        :class="props.wide ? 'max-w-2xl max-sm:h-dvh max-sm:max-h-none max-sm:rounded-none' : 'max-w-md'"
        @keydown="onKeydown"
      >
        <h2 :id="titleId" class="text-headline-small text-on-surface">{{ props.headline }}</h2>
        <div class="mt-4 mb-6 text-body-medium text-on-surface-variant">
          <slot />
        </div>
        <div class="sticky -bottom-6 -mb-6 mt-auto flex justify-end gap-2 bg-surface-container-high pb-6 pt-2">
          <slot name="actions" />
        </div>
      </div>
    </div>
  </MdTransition>
</template>
