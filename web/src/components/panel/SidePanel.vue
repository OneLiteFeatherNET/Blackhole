<script setup lang="ts">
// A side sheet: a panel for a create, edit or detail task, shown over the current page. It only emits; the
// parent decides. A dirty sheet emits request-close instead of close, so the parent can ask before input is
// discarded. Focus moves into the sheet on open and back to the opener on close. A focus trap and aria-modal
// apply only on compact width; on medium and expanded the page behind stays operable.
import { computed, onBeforeUnmount, onMounted, ref, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import { ARROW_BACK_ICON, CLOSE_ICON } from '../../icons'
import MdTransition from '../../motion/MdTransition.vue'
import MdIconButton from '../md/MdIconButton.vue'
import type { WindowClass } from './useWindowClass'

const props = withDefaults(
  defineProps<{ title: string; breadcrumb?: string[]; parentTitle?: string; sizeClass?: WindowClass; dirty?: boolean }>(),
  { breadcrumb: undefined, parentTitle: undefined, sizeClass: 'expanded', dirty: false },
)
const emit = defineEmits<{ close: []; back: []; 'request-close': [] }>()

const { t } = useI18n()
const titleId = useId()
const panel = ref<HTMLElement | null>(null)
let opener: HTMLElement | null = null

const compact = computed(() => props.sizeClass === 'compact')
const crumbs = computed(() => (props.breadcrumb?.length ? props.breadcrumb : [props.title]))
const widthClass = computed(() => {
  if (compact.value) return 'fixed inset-0 z-50 h-dvh w-full'
  return props.sizeClass === 'medium' ? 'h-full w-[400px] max-w-full shrink-0' : 'h-full w-[480px] max-w-full shrink-0'
})

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

function focusables(): HTMLElement[] {
  return panel.value ? Array.from(panel.value.querySelectorAll<HTMLElement>(FOCUSABLE)) : []
}

onMounted(() => {
  opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
  const first = focusables()[0]
  ;(first ?? panel.value)?.focus()
})

onBeforeUnmount(() => {
  if (opener?.isConnected) opener.focus()
  opener = null
})

function requestClose() {
  if (props.dirty) emit('request-close')
  else emit('close')
}

function goBack() {
  if (props.dirty) emit('request-close')
  else emit('back')
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    // Only the top sheet reacts; the stack must not also see this Escape.
    event.stopPropagation()
    requestClose()
    return
  }
  if (event.key !== 'Tab' || !compact.value || !panel.value) return

  const items = focusables()
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
  <MdTransition name="side-sheet" :appear="true">
    <div
      ref="panel"
      :role="compact ? 'dialog' : 'complementary'"
      :aria-modal="compact ? 'true' : undefined"
      :aria-labelledby="titleId"
      tabindex="-1"
      class="flex flex-col bg-surface-container-low text-on-surface shadow-md-3 outline-none"
      :class="widthClass"
      @keydown="onKeydown"
    >
      <header class="flex items-start gap-2 px-6 pt-4 pb-2">
        <MdIconButton v-if="props.parentTitle" :label="t('panel.back', { parent: props.parentTitle })" :path="ARROW_BACK_ICON" @click="goBack" />
        <div class="min-w-0 flex-1">
          <nav :aria-label="t('nav.breadcrumbs')">
            <ol class="flex flex-wrap items-center gap-1 text-body-medium text-on-surface-variant">
              <li v-for="(crumb, index) in crumbs" :key="`${index}-${crumb}`" class="flex items-center gap-1">
                <span v-if="index > 0" aria-hidden="true">›</span>
                <span :aria-current="index === crumbs.length - 1 ? 'page' : undefined">{{ crumb }}</span>
              </li>
            </ol>
          </nav>
          <h2 :id="titleId" class="mt-1 text-headline-small text-on-surface">{{ props.title }}</h2>
        </div>
        <MdIconButton :label="t('panel.close')" :path="CLOSE_ICON" @click="requestClose" />
      </header>
      <div class="min-h-0 flex-1 overflow-y-auto px-6 pb-6">
        <slot />
      </div>
    </div>
  </MdTransition>
</template>
