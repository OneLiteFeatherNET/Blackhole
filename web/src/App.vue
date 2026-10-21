<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterView, useRoute } from 'vue-router'
import MdNavigationRail from './components/md/MdNavigationRail.vue'
import MdSelect from './components/md/MdSelect.vue'
import MdTextField from './components/md/MdTextField.vue'
import MdTopAppBar from './components/md/MdTopAppBar.vue'
import type { Locale } from './i18n'
import { useUiStore, type ThemeChoice } from './stores/ui'

// Material Symbols-style paths (24dp), decorative: each item is labelled by its text.
const NAV = [
  { to: '/', key: 'dashboard', icon: 'M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z' },
  {
    to: '/players',
    key: 'players',
    icon: 'M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z',
  },
  {
    to: '/templates',
    key: 'templates',
    icon: 'M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z',
  },
  {
    to: '/sections',
    key: 'sections',
    icon: 'M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z',
  },
  { to: '/reports', key: 'reports', icon: 'M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z' },
  { to: '/appeals', key: 'appeals', icon: 'M14.4 6L14 4H5v17h2v-7h5.6l.4 2h7V6z' },
]

const { t } = useI18n()
const route = useRoute()
const ui = useUiStore()

const navItems = computed(() => NAV.map((item) => ({ to: item.to, icon: item.icon, label: t(`nav.${item.key}`) })))
const title = computed(() => t(String(route.meta.titleKey)))

const themeOptions = computed(() =>
  (['system', 'light', 'dark'] as const).map((value) => ({ value, label: t(`settings.theme.${value}`) })),
)
const localeOptions: { value: Locale; label: string }[] = [
  { value: 'de', label: 'Deutsch' },
  { value: 'en', label: 'English' },
]

const theme = computed({
  get: () => ui.theme,
  set: (value: string) => ui.setTheme(value as ThemeChoice),
})
const actingAs = computed({
  get: () => ui.actingAs,
  set: (value: string) => ui.setActingAs(value),
})
const locale = computed({
  get: () => ui.locale,
  set: (value: string) => ui.setLocale(value as Locale),
})
</script>

<template>
  <a
    href="#main"
    class="sr-only focus:not-sr-only focus:absolute focus:top-4 focus:left-4 focus:z-50 focus:rounded-md-full focus:bg-primary focus:px-4 focus:py-2 focus:text-on-primary"
  >
    {{ t('nav.skip') }}
  </a>
  <div class="flex min-h-dvh">
    <MdNavigationRail :label="t('nav.label')" :items="navItems" />
    <div class="flex min-w-0 flex-1 flex-col">
      <MdTopAppBar :title="title">
        <template #actions>
          <MdTextField v-model="actingAs" class="w-44" :label="t('settings.actingAs.label')" />
          <MdSelect v-model="theme" class="w-36" :label="t('settings.theme.label')" :options="themeOptions" />
          <MdSelect
            v-model="locale"
            class="w-36"
            :label="t('settings.locale.label')"
            :options="localeOptions"
          />
        </template>
      </MdTopAppBar>
      <main id="main" tabindex="-1" class="flex-1 p-4 md:p-6">
        <RouterView />
      </main>
    </div>
  </div>
</template>
