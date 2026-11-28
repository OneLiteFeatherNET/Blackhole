<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterView, useRoute, useRouter } from 'vue-router'
import { useApi } from './api/inject'
import { useAuth, useSignOut } from './auth/inject'
import AccountLinkDialog from './components/account/AccountLinkDialog.vue'
import AccountMenu from './components/account/AccountMenu.vue'
import RelayBanner from './components/admin/RelayBanner.vue'
import NavigationBar, { type BarItem } from './components/nav/NavigationBar.vue'
import NavigationRail, { type RailGroup, type RailItem } from './components/nav/NavigationRail.vue'
import ShortcutsHelp from './components/nav/ShortcutsHelp.vue'
import GlobalSearch from './components/search/GlobalSearch.vue'
import WorkspaceSwitcher from './components/WorkspaceSwitcher.vue'
import MdIconButton from './components/md/MdIconButton.vue'
import MdSnackbar from './components/md/MdSnackbar.vue'
import MdTopAppBar from './components/md/MdTopAppBar.vue'
import MdTransition from './motion/MdTransition.vue'
import { badgeText, useNavCountsStore, type CountKey } from './nav/navCounts'
import { BAR_DESTINATION_COUNT, visibleGroups, type WorkspaceKey } from './nav/navigation'
import { createShortcuts } from './nav/shortcuts'
import { useTicker } from './nav/ticker'
import { useBreakpoint } from './nav/useBreakpoint'
import { useSessionStore } from './stores/session'
import { KEYBOARD_ICON } from './icons'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const auth = useAuth()
const signOut = useSignOut()
const api = useApi()
const counts = useNavCountsStore()
const ticker = useTicker()
const accountOpen = ref(false)
const accountFocus = ref<'minecraft' | 'discord'>('minecraft')

function openAccount(target: 'minecraft' | 'discord'): void {
  accountFocus.value = target
  accountOpen.value = true
}
// The modal rail on compact and medium windows: the other destinations, behind "More".
const moreOpen = ref(false)
const helpOpen = ref(false)
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

// The navigation is the workspace of the current route (Moderation unless the route says otherwise). The administration
// workspace is offered to ADMIN only; the backend and the router guard stay authoritative.
const workspace = computed<WorkspaceKey>(() => (route.meta.workspace as WorkspaceKey | undefined) ?? 'moderation')
const groups = computed<RailGroup[]>(() =>
  visibleGroups({ hasAccess: session.hasAccess, administrator: session.administrator }, workspace.value).map((group) => ({
    key: group.key,
    heading: group.heading ? t(`nav.groups.${group.key}`) : undefined,
    items: group.items.map((item) => ({ to: item.to, icon: item.icon, label: t(`nav.${item.key}`), badge: badgeFor(item.count) })),
  })),
)
// The five top destinations, in order: the medium rail shows them all, the compact bar the first four.
const primaryItems = computed<RailItem[]>(() =>
  visibleGroups({ hasAccess: session.hasAccess, administrator: session.administrator }, workspace.value)
    .flatMap((group) => group.items)
    .filter((item) => item.primary)
    .map((item) => ({ to: item.to, icon: item.icon, label: t(`nav.${item.key}`), badge: badgeFor(item.count) })),
)
const barItems = computed<BarItem[]>(() => primaryItems.value.slice(0, BAR_DESTINATION_COUNT))
const showNav = computed(() => resolved.value && groups.value.length > 0)
const more = computed(() => ({ label: t('nav.more'), expanded: moreOpen.value }))
const title = computed(() => (route.meta.titleKey ? t(String(route.meta.titleKey)) : ''))

// The modal rail is only a way to pick a destination; picking one, or growing the window, closes it.
watch(
  () => route.fullPath,
  () => (moreOpen.value = false),
)
watch(size, () => (moreOpen.value = false))

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

// The chords and `?` (web/src/nav/shortcuts.ts). They only act for a signed-in user with a role.
const shortcuts = createShortcuts({
  navigate: (to) => void router.push(to),
  openHelp: () => (helpOpen.value = true),
  enabled: () => showNav.value,
})
onMounted(() => document.addEventListener('keydown', shortcuts))
onBeforeUnmount(() => document.removeEventListener('keydown', shortcuts))
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
      <template #search>
        <GlobalSearch v-if="showNav" />
      </template>
      <template #actions>
        <MdIconButton v-if="showNav" data-testid="shortcuts-help" :label="t('nav.shortcuts.open')" :path="KEYBOARD_ICON" @click="helpOpen = true" />
        <WorkspaceSwitcher v-if="resolved" />
        <AccountMenu v-if="resolved" :display-name="session.displayName" @account="openAccount" @sign-out="signOut" />
      </template>
    </MdTopAppBar>
    <div class="flex min-h-0 flex-1">
      <NavigationRail v-if="showNav && size === 'expanded'" :label="t('nav.label')" :groups="groups" variant="expanded" />
      <NavigationRail
        v-else-if="showNav && size === 'medium'"
        :label="t('nav.label')"
        :groups="[{ key: 'primary', items: primaryItems }]"
        variant="collapsed"
        :more="more"
        @more="moreOpen = true"
      />
      <main id="main" tabindex="-1" class="min-w-0 flex-1 p-4 md:p-6" :class="showNav && size === 'compact' ? 'pb-24' : ''">
        <!-- A page with PageShell renders its own heading; every other page still gets the route title. -->
        <RelayBanner v-if="resolved && session.moderator" class="mb-4" />
        <h1 v-if="resolved && title && !route.meta.pageShell" class="mb-4 text-headline-small text-on-surface">{{ title }}</h1>
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
    <NavigationBar v-if="showNav && size === 'compact'" :label="t('nav.label')" :items="barItems" :more="more" @more="moreOpen = true" />
    <NavigationRail
      v-if="showNav && size !== 'expanded'"
      :label="t('nav.label')"
      :groups="groups"
      variant="modal"
      :open="moreOpen"
      @close="moreOpen = false"
    />
  </div>
  <ShortcutsHelp :open="helpOpen" @close="helpOpen = false" />
  <AccountLinkDialog v-if="resolved && session.displayName" :open="accountOpen" :focus="accountFocus" @close="accountOpen = false" />
  <MdSnackbar :open="session.forbidden" :message="t('auth.forbidden')" @close="session.forbidden = false" />
</template>
