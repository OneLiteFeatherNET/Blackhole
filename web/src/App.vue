<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterView, useRoute } from 'vue-router'
import { useAuth, useSignOut } from './auth/inject'
import AccountLinkDialog from './components/account/AccountLinkDialog.vue'
import MdButton from './components/md/MdButton.vue'
import MdNavigationRail from './components/md/MdNavigationRail.vue'
import MdSelect from './components/md/MdSelect.vue'
import MdSnackbar from './components/md/MdSnackbar.vue'
import MdTopAppBar from './components/md/MdTopAppBar.vue'
import type { Locale } from './i18n'
import { useSessionStore } from './stores/session'
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
const session = useSessionStore()
const auth = useAuth()
const signOut = useSignOut()
const accountOpen = ref(false)

// Nothing is shown (not even the navigation) until the router has resolved a route, which for the
// first visit means after sign-in, and never while the browser is on its way to the authority.
const resolved = computed(() => route.matched.length > 0 && !auth.redirecting.value)

// Only an admin is offered the admin page; the backend stays authoritative.
const ADMIN_NAV = {
  to: '/admin',
  key: 'admin',
  icon: 'M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm0 4c1.66 0 3 1.34 3 3s-1.34 3-3 3-3-1.34-3-3 1.34-3 3-3zm0 14.3c-2.5-.8-4.5-2.9-5.4-5.3 1-1.6 3.4-2.5 5.4-2.5s4.4.9 5.4 2.5c-.9 2.4-2.9 4.5-5.4 5.3z',
}

const navItems = computed(() => (session.hasAccess ? (session.administrator ? [...NAV, ADMIN_NAV] : NAV) : []).map((item) => ({ to: item.to, icon: item.icon, label: t(`nav.${item.key}`) })))
const title = computed(() => (route.meta.titleKey ? t(String(route.meta.titleKey)) : ''))

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
    <MdNavigationRail v-if="resolved && session.hasAccess" :label="t('nav.label')" :items="navItems" />
    <div class="flex min-w-0 flex-1 flex-col">
      <MdTopAppBar :title="title">
        <template #actions>
          <template v-if="resolved && session.displayName">
            <span data-testid="signed-in-as" class="text-body-medium text-on-surface-variant">
              {{ t('auth.signedInAs', { name: session.displayName }) }}
            </span>
            <MdButton variant="text" data-testid="account" @click="accountOpen = true">{{ t('account.open') }}</MdButton>
            <MdButton variant="text" data-testid="sign-out" @click="signOut">{{ t('auth.signOut') }}</MdButton>
          </template>
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
        <!-- Always mounted so a screen reader announces the text when it appears. -->
        <p role="status" aria-live="polite" class="text-body-large" data-testid="auth-status">
          <template v-if="auth.redirecting.value">{{ t('auth.redirecting') }}</template>
          <template v-else-if="!resolved">{{ t('auth.signingIn') }}</template>
        </p>
        <RouterView v-if="resolved" />
      </main>
    </div>
  </div>
  <AccountLinkDialog v-if="resolved && session.displayName" :open="accountOpen" @close="accountOpen = false" />
  <MdSnackbar :open="session.forbidden" :message="t('auth.forbidden')" @close="session.forbidden = false" />
</template>
