<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterView, useRoute } from 'vue-router'
import { useApi } from './api/inject'
import { useAuth, useSignOut } from './auth/inject'
import AccountLinkDialog from './components/account/AccountLinkDialog.vue'
import AccountMenu from './components/account/AccountMenu.vue'
import GlobalSearch from './components/search/GlobalSearch.vue'
import MdIconButton from './components/md/MdIconButton.vue'
import MdNavigationDrawer from './components/md/MdNavigationDrawer.vue'
import MdSnackbar from './components/md/MdSnackbar.vue'
import MdTopAppBar from './components/md/MdTopAppBar.vue'
import MdTransition from './motion/MdTransition.vue'
import { badgeText, useNavCountsStore, type CountKey } from './nav/navCounts'
import { visibleGroups } from './nav/navigation'
import { useTicker } from './nav/ticker'
import { useBreakpoint } from './nav/useBreakpoint'
import { useSessionStore } from './stores/session'
import { useUiStore } from './stores/ui'

const MENU_ICON = 'M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z'

const { t } = useI18n()
const route = useRoute()
const ui = useUiStore()
const session = useSessionStore()
const auth = useAuth()
const signOut = useSignOut()
const api = useApi()
const counts = useNavCountsStore()
const ticker = useTicker()
const accountOpen = ref(false)
const navOpen = ref(false)
const size = useBreakpoint()

// Nothing is shown (not even the navigation) until the router has resolved a route, which for the
// first visit means after sign-in, and never while the browser is on its way to the authority.
const resolved = computed(() => route.matched.length > 0 && !auth.redirecting.value)

/** The badge of a destination with open work; none at 0 or while the count is unknown. */
function badgeFor(key: CountKey | undefined) {
  const count = key ? counts[key] : null
  const text = badgeText(count)
  return key && text ? { text, label: t(`nav.badge.${key}`, { n: count }, count ?? 0) } : undefined
}

// Only an admin is offered the admin page; the backend stays authoritative.
const groups = computed(() =>
  visibleGroups({ hasAccess: session.hasAccess, administrator: session.administrator }).map((group) => ({
    key: group.key,
    heading: group.heading ? t(`nav.groups.${group.key}`) : undefined,
    items: group.items.map((item) => ({ to: item.to, icon: item.icon, label: t(`nav.${item.key}`), badge: badgeFor(item.count) })),
  })),
)
const showNav = computed(() => resolved.value && groups.value.length > 0)
// Compact windows get a modal drawer; medium ones always a rail; expanded ones the drawer, which the viewer may fold into a rail.
const navMode = computed(() => (size.value === 'compact' ? 'modal' : size.value === 'medium' || ui.navCollapsed ? 'rail' : 'drawer'))
const menuButton = computed(() => {
  if (navMode.value === 'modal') return { label: t('nav.open'), expanded: navOpen.value }
  return ui.navCollapsed ? { label: t('nav.expand'), expanded: false } : { label: t('nav.collapse'), expanded: true }
})
const title = computed(() => (route.meta.titleKey ? t(String(route.meta.titleKey)) : ''))

function onMenuButton(): void {
  if (navMode.value === 'modal') navOpen.value = !navOpen.value
  else ui.setNavCollapsed(!ui.navCollapsed)
}

// The modal drawer is only a way to pick a destination; picking one, or growing the window, closes it.
watch(
  () => route.fullPath,
  () => (navOpen.value = false),
)
watch(navMode, () => (navOpen.value = false))

// Open counts for the badges: when the navigation appears, on every navigation and once a minute.
const COUNT_REFRESH_MS = 60_000
let stopTicker: (() => void) | null = null
watch(
  showNav,
  (shown) => {
    stopTicker?.()
    stopTicker = null
    if (!shown) return
    void counts.refresh(api)
    stopTicker = ticker.every(COUNT_REFRESH_MS, () => void counts.refresh(api))
  },
  { immediate: true },
)
watch(
  () => route.fullPath,
  () => showNav.value && void counts.refresh(api),
)
onBeforeUnmount(() => stopTicker?.())
</script>

<template>
  <a
    href="#main"
    class="sr-only focus:not-sr-only focus:absolute focus:top-4 focus:left-4 focus:z-50 focus:rounded-md-full focus:bg-primary focus:px-4 focus:py-2 focus:text-on-primary"
  >
    {{ t('nav.skip') }}
  </a>
  <div class="flex min-h-dvh flex-col">
    <MdTopAppBar :brand="t('nav.brand')">
      <template #leading>
        <MdIconButton
          v-if="showNav && size !== 'medium'"
          data-testid="nav-toggle"
          :label="menuButton.label"
          :path="MENU_ICON"
          :aria-expanded="menuButton.expanded"
          @click="onMenuButton"
        />
      </template>
      <template #search>
        <GlobalSearch v-if="showNav" />
      </template>
      <template #actions>
        <AccountMenu v-if="resolved" :display-name="session.displayName" @account="accountOpen = true" @sign-out="signOut" />
      </template>
    </MdTopAppBar>
    <div class="flex min-h-0 flex-1">
      <MdNavigationDrawer
        v-if="showNav"
        :label="t('nav.label')"
        :groups="groups"
        :mode="navMode"
        :open="navOpen"
        @close="navOpen = false"
      />
      <main id="main" tabindex="-1" class="min-w-0 flex-1 p-4 md:p-6">
        <h1 v-if="resolved && title" class="mb-4 text-headline-small text-on-surface">{{ title }}</h1>
        <!-- Always mounted so a screen reader announces the text when it appears. -->
        <p role="status" aria-live="polite" class="text-body-large" data-testid="auth-status">
          <template v-if="auth.redirecting.value">{{ t('auth.redirecting') }}</template>
          <template v-else-if="!resolved">{{ t('auth.signingIn') }}</template>
        </p>
        <RouterView v-if="resolved" v-slot="{ Component }">
          <MdTransition name="fade-through" mode="out-in"><component :is="Component" /></MdTransition>
        </RouterView>
      </main>
    </div>
  </div>
  <AccountLinkDialog v-if="resolved && session.displayName" :open="accountOpen" @close="accountOpen = false" />
  <MdSnackbar :open="session.forbidden" :message="t('auth.forbidden')" @close="session.forbidden = false" />
</template>
