<script setup lang="ts">
// The workspace switcher: a dropdown at the top right of the app bar, left of the account menu. Shown to ADMIN only:
// every other role has the Moderation workspace alone and sees no switcher. The button names the current workspace;
// the menu lists both with an icon and a short description, marks the current one with a check and `aria-current`,
// and follows the ARIA menu button pattern of RowMenu: click or ArrowDown opens it and focuses the first item; arrows,
// Home and End move between items; Escape closes it and returns the focus to the button; Tab or a press outside closes it.
// Choosing an entry opens that workspace's home. On narrow screens the name is hidden and only the icon stays.
import { computed, nextTick, onBeforeUnmount, ref, useId, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { ADMIN_ICON, CHECK_ICON, DROP_DOWN_ICON, INBOX_ICON } from '../icons'
import type { WorkspaceKey } from '../nav/navigation'
import { storeWorkspace, visibleWorkspaces, type Workspace } from '../nav/workspaces'
import { useSessionStore } from '../stores/session'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const open = ref(false)
const uid = useId()
const root = ref<HTMLElement | null>(null)
const trigger = ref<HTMLButtonElement | null>(null)
const menu = ref<HTMLElement | null>(null)

const ICONS: Record<WorkspaceKey, string> = { moderation: INBOX_ICON, administration: ADMIN_ICON }
const HINTS: Record<WorkspaceKey, string> = { moderation: 'workspace.moderationHint', administration: 'workspace.administrationHint' }

const entries = computed(() =>
  visibleWorkspaces({ hasAccess: session.hasAccess, administrator: session.administrator }).map((workspace) => ({
    ...workspace,
    icon: ICONS[workspace.key],
    hint: t(HINTS[workspace.key]),
  })),
)
/** The workspace of the current route; a route without one belongs to Moderation. */
const current = computed<WorkspaceKey>(() => (route.meta.workspace as WorkspaceKey | undefined) ?? 'moderation')
const currentEntry = computed(() => entries.value.find((entry) => entry.key === current.value) ?? entries.value[0])

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

function toggle(): void {
  if (open.value) close(true)
  else void openMenu()
}

async function choose(workspace: Workspace): Promise<void> {
  open.value = false
  storeWorkspace(workspace.key)
  trigger.value?.focus()
  await router.push(workspace.home)
}

function onMenuKeydown(event: KeyboardEvent): void {
  const items = itemElements()
  const index = items.indexOf(document.activeElement as HTMLElement)
  switch (event.key) {
    case 'ArrowDown':
      focusItem(index + 1)
      break
    case 'ArrowUp':
      focusItem(index < 0 ? items.length - 1 : index - 1)
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

function onPointerOutside(event: Event): void {
  if (root.value?.contains(event.target as Node)) return
  close(false)
}

watch(open, (isOpen) => {
  if (isOpen) document.addEventListener('pointerdown', onPointerOutside, true)
  else document.removeEventListener('pointerdown', onPointerOutside, true)
})
onBeforeUnmount(() => document.removeEventListener('pointerdown', onPointerOutside, true))
</script>

<template>
  <div ref="root" v-if="entries.length > 1" class="relative" @focusout="onFocusOut">
    <button
      ref="trigger"
      type="button"
      data-testid="workspace-switcher"
      aria-haspopup="menu"
      :aria-expanded="open ? 'true' : 'false'"
      :aria-controls="open ? `${uid}-menu` : undefined"
      :title="t('workspace.switch')"
      class="md-state flex h-10 items-center gap-1 rounded-md-full px-3 text-label-large text-on-surface"
      :class="open ? 'bg-secondary-container text-on-secondary-container' : 'text-on-surface'"
      @click="toggle"
      @keydown.down.prevent="openMenu()"
    >
      <svg viewBox="0 0 24 24" class="h-5 w-5 shrink-0" aria-hidden="true"><path :d="currentEntry?.icon" fill="currentColor" /></svg>
      <span class="max-sm:sr-only">{{ currentEntry ? t(currentEntry.label) : '' }}</span>
      <svg viewBox="0 0 24 24" class="h-5 w-5 shrink-0" aria-hidden="true"><path :d="DROP_DOWN_ICON" fill="currentColor" /></svg>
    </button>
    <div
      v-if="open"
      :id="`${uid}-menu`"
      ref="menu"
      data-testid="workspace-menu"
      role="menu"
      :aria-label="t('workspace.switch')"
      class="absolute top-full right-0 z-40 mt-1 flex w-72 max-w-[calc(100vw-2rem)] flex-col gap-1 rounded-md-md bg-surface-container-high p-2 shadow-md-2"
      @keydown="onMenuKeydown"
    >
      <button
        v-for="entry in entries"
        :key="entry.key"
        type="button"
        role="menuitem"
        tabindex="-1"
        :data-testid="`workspace-${entry.key}`"
        :aria-current="current === entry.key ? 'true' : undefined"
        class="md-state flex items-start gap-3 rounded-md-md px-3 py-2 text-left"
        :class="current === entry.key ? 'bg-secondary-container text-on-secondary-container' : 'text-on-surface'"
        @click="choose(entry)"
      >
        <svg viewBox="0 0 24 24" class="h-5 w-5 shrink-0" aria-hidden="true"><path :d="entry.icon" fill="currentColor" /></svg>
        <span class="flex min-w-0 flex-1 flex-col">
          <span data-testid="workspace-label" class="text-label-large">{{ t(entry.label) }}</span>
          <span data-testid="workspace-hint" class="text-body-small text-on-surface-variant">{{ entry.hint }}</span>
        </span>
        <svg v-if="current === entry.key" data-testid="workspace-check" viewBox="0 0 24 24" class="h-5 w-5 shrink-0" aria-hidden="true"><path :d="CHECK_ICON" fill="currentColor" /></svg>
      </button>
    </div>
  </div>
</template>
