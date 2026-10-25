<script lang="ts">
// A secondary action of a row. `destructive` items are shown in the error colour and are confirmed by the page.
export interface RowMenuItem {
  key: string
  label: string
  destructive?: boolean
}
</script>

<script setup lang="ts">
// The overflow of a table row (design section 2, "Row actions"). The trigger is an icon button named after its row.
// It follows the ARIA menu button pattern: click or ArrowDown opens the menu and focuses the first item; arrows, Home
// and End move between items (wrapping); Escape closes it and returns the focus to the trigger; Tab or a click
// outside closes it.
import { nextTick, ref, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import { MORE_ICON } from '../icons'

const props = defineProps<{ label: string; items: RowMenuItem[] }>()
const emit = defineEmits<{ select: [key: string] }>()

const { t } = useI18n()
const open = ref(false)
const root = ref<HTMLElement | null>(null)
const trigger = ref<HTMLButtonElement | null>(null)
const menu = ref<HTMLElement | null>(null)

const menuId = useId()

function itemElements(): HTMLElement[] {
  return Array.from(menu.value?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? [])
}

function focusItem(index: number): void {
  const items = itemElements()
  if (items.length === 0) return
  items[(index + items.length) % items.length]?.focus()
}

async function openMenu(): Promise<void> {
  open.value = true
  await nextTick()
  focusItem(0)
}

function close(returnFocus: boolean): void {
  open.value = false
  if (returnFocus) void nextTick(() => trigger.value?.focus())
}

function choose(key: string): void {
  open.value = false
  trigger.value?.focus()
  emit('select', key)
}

function onMenuKeydown(event: KeyboardEvent): void {
  const items = itemElements()
  const current = items.indexOf(document.activeElement as HTMLElement)
  switch (event.key) {
    case 'ArrowDown':
      focusItem(current + 1)
      break
    case 'ArrowUp':
      focusItem(current < 0 ? items.length - 1 : current - 1)
      break
    case 'Home':
      focusItem(0)
      break
    case 'End':
      focusItem(items.length - 1)
      break
    case 'Escape':
      close(true)
      break
    case 'Tab':
      close(false)
      return
    default:
      return
  }
  event.preventDefault()
}

// Focus that leaves the whole control (not into the menu) closes the menu.
function onFocusOut(event: FocusEvent): void {
  const next = event.relatedTarget as Node | null
  if (open.value && !(next && root.value?.contains(next))) close(false)
}
</script>

<template>
  <div ref="root" class="relative inline-flex" @focusout="onFocusOut">
    <button
      ref="trigger"
      type="button"
      aria-haspopup="menu"
      :aria-expanded="open ? 'true' : 'false'"
      :aria-controls="open ? menuId : undefined"
      :aria-label="t('rowMenu.more', { name: props.label })"
      :title="t('rowMenu.more', { name: props.label })"
      class="md-state grid h-10 w-10 place-items-center rounded-md-full text-on-surface-variant"
      @click="open ? close(true) : openMenu()"
      @keydown.down.prevent="openMenu()"
    >
      <svg aria-hidden="true" viewBox="0 0 24 24" class="h-6 w-6"><path :d="MORE_ICON" fill="currentColor" /></svg>
    </button>
    <div
      v-if="open"
      :id="menuId"
      ref="menu"
      role="menu"
      class="absolute top-full right-0 z-10 mt-1 flex min-w-44 flex-col rounded-md-md border border-outline-variant bg-surface-container-high py-1"
      @keydown="onMenuKeydown"
    >
      <button
        v-for="item in props.items"
        :key="item.key"
        type="button"
        role="menuitem"
        tabindex="-1"
        class="md-state flex h-10 items-center px-4 text-left text-body-medium whitespace-nowrap"
        :class="item.destructive ? 'text-error' : 'text-on-surface'"
        @click="choose(item.key)"
      >
        {{ item.label }}
      </button>
    </div>
  </div>
</template>
