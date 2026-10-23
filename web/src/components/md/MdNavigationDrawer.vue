<script setup lang="ts">
// MD3 navigation drawer with groups. One component for the three window size classes:
//  - drawer: standard drawer, icon and label side by side
//  - rail:   navigation rail, icon with the label below, headings replaced by dividers
//  - modal:  modal drawer over a scrim, only rendered while open (Escape and the scrim close it)
import { nextTick, ref, useId, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import MdTransition from '../../motion/MdTransition.vue'

export interface DrawerItem {
  to: string
  label: string
  icon: string
  /** Open-work count: `text` is shown, `label` is what a screen reader hears (it carries the real number). */
  badge?: { text: string; label: string }
}
export interface DrawerGroup {
  key: string
  /** Absent for a group that stands without a heading. */
  heading?: string
  items: DrawerItem[]
}

const props = withDefaults(
  defineProps<{
    label: string
    groups: DrawerGroup[]
    mode: 'drawer' | 'rail' | 'modal'
    /** Modal only. */
    open?: boolean
  }>(),
  { open: false },
)
const emit = defineEmits<{ close: [] }>()

const route = useRoute()
const uid = useId()
const root = ref<HTMLElement | null>(null)
let returnFocusTo: HTMLElement | null = null

// An entry stays current on its sub pages, e.g. /players for /players/<owner>.
function isCurrent(to: string): boolean {
  return route.path === to || (to !== '/' && route.path.startsWith(`${to}/`))
}

const visible = () => props.mode !== 'modal' || props.open
const links = () => Array.from(root.value?.querySelectorAll<HTMLElement>('a[href]') ?? [])

// A modal drawer takes the focus when it opens (on the current entry) and hands it back when it closes.
watch(
  () => props.mode === 'modal' && props.open,
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
  if (event.key === 'Escape' && props.mode === 'modal') {
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
  } else if (event.key === 'Tab' && props.mode === 'modal') {
    // Keep Tab within the modal drawer.
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
    <div v-if="visible()" :class="props.mode === 'modal' ? 'fixed inset-0 z-50' : 'contents'">
      <div v-if="props.mode === 'modal'" data-testid="nav-scrim" class="absolute inset-0 bg-scrim/[0.32]" @click="emit('close')" />
      <nav
        ref="root"
        :key="props.mode"
        :aria-label="props.label"
        :aria-modal="props.mode === 'modal' ? 'true' : undefined"
        class="flex shrink-0 flex-col overflow-y-auto bg-surface-container-low text-on-surface"
        :class="{
          'md-fade-in': props.mode !== 'modal',
          'sticky top-16 h-[calc(100dvh-4rem)] w-72 gap-1 px-3 py-3': props.mode === 'drawer',
          'sticky top-16 h-[calc(100dvh-4rem)] w-20 items-center gap-1 py-3': props.mode === 'rail',
          'relative h-full w-80 max-w-[85vw] gap-1 rounded-r-md-xl px-3 py-3 shadow-md-3': props.mode === 'modal',
        }"
        @keydown="onKeydown"
      >
        <template v-for="(group, index) in props.groups" :key="group.key">
          <hr v-if="index > 0 && props.mode === 'rail'" class="my-1 w-8 border-outline-variant" />
          <div :role="group.heading ? 'group' : undefined" :aria-labelledby="group.heading ? `${uid}-${group.key}` : undefined" class="flex flex-col gap-1" :class="{ 'w-full': props.mode === 'rail', 'mt-2': index > 0 && props.mode !== 'rail' }">
            <p
              v-if="group.heading"
              :id="`${uid}-${group.key}`"
              class="px-4 pt-3 pb-1 text-label-large text-on-surface-variant"
              :class="{ 'sr-only': props.mode === 'rail' }"
            >
              {{ group.heading }}
            </p>
            <RouterLink v-for="item in group.items" :key="item.to" v-slot="{ href, navigate }" :to="item.to" custom>
              <a
                :href="href"
                :aria-current="isCurrent(item.to) ? 'page' : undefined"
                class="group text-label-large text-on-surface outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
                :class="
                  props.mode === 'rail'
                    ? 'flex w-full flex-col items-center gap-1 rounded-md-md py-1 text-label-medium'
                    : [
                        'md-state md-colors flex h-14 items-center gap-3 rounded-md-full px-4',
                        isCurrent(item.to) ? 'bg-secondary-container text-on-secondary-container' : 'text-on-surface-variant',
                      ]
                "
                @click="navigate"
              >
                <span
                  v-if="props.mode === 'rail'"
                  class="md-state md-colors grid h-8 w-14 place-items-center rounded-md-full group-hover:before:opacity-[0.08] group-focus-visible:before:opacity-[0.12]"
                  :class="isCurrent(item.to) ? 'bg-secondary-container text-on-secondary-container' : 'text-on-surface-variant'"
                >
                  <svg viewBox="0 0 24 24" class="h-6 w-6" aria-hidden="true"><path :d="item.icon" fill="currentColor" /></svg>
                  <MdTransition name="badge" mode="out-in">
                    <span v-if="item.badge" :key="item.badge.text" data-testid="nav-badge" aria-hidden="true" class="absolute -top-1 right-0 grid h-4 min-w-4 place-items-center rounded-md-full bg-error px-1 text-label-small text-on-error">{{ item.badge.text }}</span>
                </MdTransition>
              </span>
              <template v-else>
                <svg viewBox="0 0 24 24" class="h-6 w-6 shrink-0" aria-hidden="true"><path :d="item.icon" fill="currentColor" /></svg>
              </template>
              <span :class="props.mode === 'rail' ? 'text-center' : 'min-w-0 flex-1 truncate'">{{ item.label }}</span>
              <MdTransition name="badge" mode="out-in">
                <span v-if="item.badge && props.mode !== 'rail'" :key="item.badge.text" data-testid="nav-badge" aria-hidden="true" class="grid h-6 min-w-6 place-items-center rounded-md-full bg-error px-2 text-label-medium text-on-error">{{ item.badge.text }}</span>
              </MdTransition>
              <span v-if="item.badge" class="sr-only">{{ item.badge.label }}</span>
            </a>
          </RouterLink>
        </div>
      </template>
    </nav>
  </div>
  </MdTransition>
</template>
