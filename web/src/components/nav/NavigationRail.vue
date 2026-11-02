<script setup lang="ts">
// The side navigation of the panel, in the three MD3 window size classes (no navigation drawer):
//  - collapsed: a navigation rail, 80dp wide, icon with the label below; the five top destinations and a More button
//  - expanded:  a standard navigation rail, 288dp wide, always open, icon and label side by side under group headings
//  - modal:     the expanded rail as a modal over a scrim, only rendered while open (Escape and the scrim close it)
import { nextTick, ref, useId, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { MORE_ICON } from '../../icons'
import MdTransition from '../../motion/MdTransition.vue'

export interface RailItem {
  to: string
  label: string
  icon: string
  /** Open-work count: `text` is shown, `label` is what a screen reader hears (it carries the real number). */
  badge?: { text: string; label: string }
}
export interface RailGroup {
  key: string
  /** Absent for a group that stands without a heading. */
  heading?: string
  items: RailItem[]
}

const props = withDefaults(
  defineProps<{
    label: string
    groups: RailGroup[]
    variant: 'collapsed' | 'expanded' | 'modal'
    /** Modal only. */
    open?: boolean
    /** Collapsed only: the button that opens the modal rail with the other destinations. */
    more?: { label: string; expanded: boolean }
  }>(),
  { open: false, more: undefined },
)
const emit = defineEmits<{ close: []; more: [] }>()

const route = useRoute()
const uid = useId()
const root = ref<HTMLElement | null>(null)
let returnFocusTo: HTMLElement | null = null

// An entry is current on its own page, or on a detail page whose route names it (`meta.nav`, e.g. /players for
// /players/<owner>). Never by a prefix of the path: /cases is not current on /casesfoo.
function isCurrent(to: string): boolean {
  return route.path === to || route.meta.nav === to
}

const visible = () => props.variant !== 'modal' || props.open
const links = () => Array.from(root.value?.querySelectorAll<HTMLElement>('a[href]') ?? [])

// A modal rail takes the focus when it opens (on the current entry) and hands it back when it closes.
watch(
  () => props.variant === 'modal' && props.open,
  async (isOpen) => {
    if (isOpen) {
      returnFocusTo = document.activeElement instanceof HTMLElement ? document.activeElement : null
      await nextTick()
      const all = links()
      ;(all.find((link) => link.getAttribute('aria-current') === 'page') ?? all[0])?.focus()
    } else {
      returnFocusTo?.focus()
      returnFocusTo = null
    }
  },
)

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape' && props.variant === 'modal') {
    event.preventDefault()
    emit('close')
    return
  }
  const all = links()
  if (all.length === 0) return
  const index = all.indexOf(document.activeElement as HTMLElement)

  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    const step = event.key === 'ArrowDown' ? 1 : -1
    all[index < 0 ? 0 : (index + step + all.length) % all.length]!.focus()
  } else if (event.key === 'Home' || event.key === 'End') {
    event.preventDefault()
    all[event.key === 'Home' ? 0 : all.length - 1]!.focus()
  } else if (event.key === 'Tab' && props.variant === 'modal') {
    // Keep Tab within the modal rail.
    if (event.shiftKey && index === 0) {
      event.preventDefault()
      all[all.length - 1]!.focus()
    } else if (!event.shiftKey && index === all.length - 1) {
      event.preventDefault()
      all[0]!.focus()
    }
  }
}
</script>

<template>
  <MdTransition name="modal">
    <div v-if="visible()" :class="props.variant === 'modal' ? 'fixed inset-0 z-50' : 'contents'">
      <div v-if="props.variant === 'modal'" data-testid="nav-scrim" class="absolute inset-0 bg-scrim/[0.32]" @click="emit('close')" />
      <nav
        ref="root"
        :key="props.variant"
        :aria-label="props.label"
        :aria-modal="props.variant === 'modal' ? 'true' : undefined"
        class="flex shrink-0 flex-col overflow-y-auto bg-surface-container-low text-on-surface"
        :class="{
          'md-fade-in': props.variant !== 'modal',
          'sticky top-16 h-(--md-drawer-height) w-20 items-center gap-1 py-3': props.variant === 'collapsed',
          'sticky top-16 h-(--md-drawer-height) w-72 gap-1 px-3 py-3': props.variant === 'expanded',
          'relative h-full w-80 max-w-[85vw] gap-1 rounded-r-md-xl px-3 py-3 shadow-md-3': props.variant === 'modal',
        }"
        @keydown="onKeydown"
      >
        <template v-for="(group, index) in props.groups" :key="group.key">
          <hr v-if="index > 0 && props.variant === 'collapsed'" class="my-1 w-8 border-outline-variant" />
          <div
            :role="group.heading ? 'group' : undefined"
            :aria-labelledby="group.heading ? `${uid}-${group.key}` : undefined"
            class="flex flex-col gap-1"
            :class="{ 'w-full': props.variant === 'collapsed', 'mt-2': index > 0 && props.variant !== 'collapsed' }"
          >
            <p
              v-if="group.heading"
              :id="`${uid}-${group.key}`"
              class="px-4 pt-3 pb-1 text-label-large text-on-surface-variant"
              :class="{ 'sr-only': props.variant === 'collapsed' }"
            >
              {{ group.heading }}
            </p>
            <RouterLink v-for="item in group.items" :key="item.to" v-slot="{ href, navigate }" :to="item.to" custom>
              <a
                :href="href"
                :aria-current="isCurrent(item.to) ? 'page' : undefined"
                class="group text-label-large text-on-surface min-h-12 focus-ring"
                :class="
                  props.variant === 'collapsed'
                    ? 'flex w-full flex-col items-center gap-1 rounded-md-md py-1 text-label-medium'
                    : [
                        'md-state md-colors flex h-14 items-center gap-3 rounded-md-full px-4',
                        isCurrent(item.to) ? 'bg-secondary-container text-on-secondary-container' : 'text-on-surface-variant',
                      ]
                "
                @click="navigate"
              >
                <span
                  v-if="props.variant === 'collapsed'"
                  class="md-state md-colors relative grid h-8 w-14 place-items-center rounded-md-full group-hover:before:opacity-[0.08] group-focus-visible:before:opacity-[0.12]"
                  :class="isCurrent(item.to) ? 'bg-secondary-container text-on-secondary-container' : 'text-on-surface-variant'"
                >
                  <svg viewBox="0 0 24 24" class="h-6 w-6" aria-hidden="true"><path :d="item.icon" fill="currentColor" /></svg>
                  <MdTransition name="badge" mode="out-in">
                    <span
                      v-if="item.badge"
                      :key="item.badge.text"
                      data-testid="nav-badge"
                      aria-hidden="true"
                      class="absolute -top-1 right-0 grid h-4 min-w-4 place-items-center rounded-md-full bg-error px-1 text-label-medium text-on-error"
                    >
                      {{ item.badge.text }}
                    </span>
                  </MdTransition>
                </span>
                <template v-else>
                  <svg viewBox="0 0 24 24" class="h-6 w-6 shrink-0" aria-hidden="true"><path :d="item.icon" fill="currentColor" /></svg>
                </template>
                <span :class="props.variant === 'collapsed' ? 'text-center' : 'min-w-0 flex-1 truncate'">{{ item.label }}</span>
                <MdTransition v-if="props.variant !== 'collapsed'" name="badge" mode="out-in">
                  <span
                    v-if="item.badge"
                    :key="item.badge.text"
                    data-testid="nav-badge"
                    aria-hidden="true"
                    class="grid h-6 min-w-6 place-items-center rounded-md-full bg-error px-2 text-label-medium text-on-error"
                  >
                    {{ item.badge.text }}
                  </span>
                </MdTransition>
                <span v-if="item.badge" class="sr-only">{{ item.badge.label }}</span>
              </a>
            </RouterLink>
          </div>
        </template>
        <button
          v-if="props.variant === 'collapsed' && props.more"
          type="button"
          data-testid="nav-more"
          aria-haspopup="dialog"
          :aria-expanded="props.more.expanded"
          class="md-state md-colors group flex min-h-12 w-full flex-col items-center gap-1 rounded-md-md py-1 text-label-medium text-on-surface-variant focus-ring"
          @click="emit('more')"
        >
          <span class="grid h-8 w-14 place-items-center rounded-md-full" :class="props.more.expanded ? 'bg-secondary-container text-on-secondary-container' : ''">
            <svg viewBox="0 0 24 24" class="h-6 w-6" aria-hidden="true"><path :d="MORE_ICON" fill="currentColor" /></svg>
          </span>
          <span class="text-center">{{ props.more.label }}</span>
        </button>
      </nav>
    </div>
  </MdTransition>
</template>
