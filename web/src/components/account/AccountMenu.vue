<script setup lang="ts">
// The signed-in user's menu in the top bar: who is signed in, the account dialog, theme, language and
// sign-out. A non-modal popover: Escape and a press outside close it, focus returns to the button.
// The option lists of the two selects are teleported to <body>, so a press there is not "outside".
import { computed, nextTick, onBeforeUnmount, ref, useId, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Locale } from '../../i18n'
import { useUiStore, type ThemeChoice } from '../../stores/ui'
import MdButton from '../md/MdButton.vue'
import MdSelect from '../md/MdSelect.vue'
import MdTransition from '../../motion/MdTransition.vue'

const props = defineProps<{ displayName: string | null }>()
// `account` names the own-account panel to open: the Minecraft link, or the Discord link inside the same dialog.
const emit = defineEmits<{ account: [target: 'minecraft' | 'discord']; signOut: [] }>()

const { t } = useI18n()
const ui = useUiStore()
const open = ref(false)
const uid = useId()
const root = ref<HTMLElement | null>(null)
const trigger = ref<HTMLElement | null>(null)

const initial = computed(() => (props.displayName?.trim()[0] ?? '').toUpperCase())

const themeOptions = computed(() => (['system', 'light', 'dark'] as const).map((value) => ({ value, label: t(`settings.theme.${value}`) })))
const localeOptions: { value: Locale; label: string }[] = [
  { value: 'de', label: 'Deutsch' },
  { value: 'en', label: 'English' },
]
const theme = computed({
  get: () => ui.theme,
  set: (value: string) => ui.setTheme(value as ThemeChoice),
})
const locale = computed({
  get: () => ui.locale,
  set: (value: string) => ui.setLocale(value as Locale),
})

function close(returnFocus = true): void {
  open.value = false
  if (returnFocus) void nextTick(() => trigger.value?.focus())
}

function onPointerOutside(event: Event): void {
  const target = event.target as Element
  if (root.value?.contains(target) || target.closest?.('[role="listbox"]')) return
  close(false)
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Escape') return
  event.preventDefault()
  event.stopPropagation()
  close()
}

function chooseAccount(target: 'minecraft' | 'discord'): void {
  close(false)
  emit('account', target)
}

function chooseSignOut(): void {
  close(false)
  emit('signOut')
}

watch(open, (isOpen) => {
  if (isOpen) document.addEventListener('pointerdown', onPointerOutside, true)
  else document.removeEventListener('pointerdown', onPointerOutside, true)
})
onBeforeUnmount(() => document.removeEventListener('pointerdown', onPointerOutside, true))
</script>

<template>
  <div ref="root" class="relative" @keydown="onKeydown">
    <button
      ref="trigger"
      type="button"
      data-testid="account-menu"
      :aria-label="t('account.menu')"
      :title="t('account.menu')"
      :aria-expanded="open"
      :aria-controls="`${uid}-panel`"
      aria-haspopup="true"
      class="md-state grid h-10 w-10 place-items-center rounded-md-full bg-primary-container text-title-medium text-on-primary-container"
      @click="open = !open"
    >
      <template v-if="initial">{{ initial }}</template>
      <svg v-else viewBox="0 0 24 24" class="h-6 w-6" aria-hidden="true">
        <path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z" fill="currentColor" />
      </svg>
    </button>
    <MdTransition name="menu">
      <div
        v-if="open"
        :id="`${uid}-panel`"
        role="group"
        :aria-label="t('account.menu')"
        style="--md-menu-origin: top right"
        class="absolute right-0 top-12 z-40 flex w-72 flex-col gap-3 rounded-md-md bg-surface-container p-4 text-on-surface shadow-md-2"
      >
        <template v-if="props.displayName">
          <p data-testid="signed-in-as" class="text-body-medium text-on-surface-variant">{{ t('auth.signedInAs', { name: props.displayName }) }}</p>
          <MdButton variant="tonal" data-testid="link-minecraft" @click="chooseAccount('minecraft')">{{ t('account.linkMinecraft') }}</MdButton>
          <MdButton variant="tonal" data-testid="link-discord" @click="chooseAccount('discord')">{{ t('account.linkDiscord') }}</MdButton>
        </template>
        <MdSelect v-model="theme" :label="t('settings.theme.label')" :options="themeOptions" />
        <MdSelect v-model="locale" :label="t('settings.locale.label')" :options="localeOptions" />
        <MdButton v-if="props.displayName" variant="outlined" data-testid="sign-out" @click="chooseSignOut">{{ t('auth.signOut') }}</MdButton>
      </div>
    </MdTransition>
  </div>
</template>
