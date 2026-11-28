<script setup lang="ts">
import { computed, onMounted, provide, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { useApi } from '../../api/inject'
import type { ApiError, EloEvent, EloProfile, Punishment, PunishmentProfile } from '../../api/types'
import { useResource } from '../../composables/useResource'
import { formatDateTime } from '../../format'
import { useLookupStore } from '../../stores/lookup'
import { useSessionStore } from '../../stores/session'
import type { Crumb } from '../Breadcrumbs.vue'
import EntityHeader from '../entity/EntityHeader.vue'
import type { EntityAction, EntityStateView } from '../entity/entityHeader'
import { playerStatePill } from '../entity/entityState'
import ExemptionBadge from '../exemption/ExemptionBadge.vue'
import MdButton from '../md/MdButton.vue'
import MdSnackbar from '../md/MdSnackbar.vue'
import MdTabs from '../md/MdTabs.vue'
import { panelDomId, tabDomId } from '../md/tabs'
import ResourceState from '../ResourceState.vue'
import ClientRuleHits from '../clientrules/ClientRuleHits.vue'
import ActivePunishments from './ActivePunishments.vue'
import EloHistory from './EloHistory.vue'
import EvasionHints from './EvasionHints.vue'
import IntelHitsSection from './IntelHitsSection.vue'
import NameHistory from './NameHistory.vue'
import PlayerCases from './PlayerCases.vue'
import PlayerReports from './PlayerReports.vue'
import { PLAYER_KEY, type PlayerContext } from './playerContext'
import PlayerSessions from './PlayerSessions.vue'
import PunishmentHistory from './PunishmentHistory.vue'
import { reasonOf } from './punishmentInfo'
import SectionScores from './SectionScores.vue'

// The page's header (EntityHeader) is part of this component: its state and its actions come from the profile.
const props = defineProps<{ owner: string; title: string; description?: string; crumbs: Crumb[] }>()

// The tabs of the player (`?tab=`, design decision 7 row 11). An unknown or missing value is the overview.
const TAB_IDS = ['overview', 'punishments', 'reports', 'cases', 'evasion', 'elo', 'names', 'intel', 'warnings', 'evidence'] as const
type PlayerTab = (typeof TAB_IDS)[number]
const TAB_PREFIX = 'player'

const { t, locale } = useI18n()
const api = useApi()
const session = useSessionStore()
const lookup = useLookupStore()
const route = useRoute()
const router = useRouter()

// Profile and scores are loaded together so every action can refresh both with one reload.
const player = useResource(async () => {
  const [profile, elo] = await Promise.all([api.profile(props.owner), api.eloProfile(props.owner)])
  if (!profile.ok) return profile
  if (!elo.ok) return elo
  return { ok: true as const, data: { profile: profile.data, elo: elo.data } as { profile: PunishmentProfile; elo: EloProfile } }
})

const activeTab = computed<PlayerTab>(() => TAB_IDS.find((tab) => tab === route.query.tab) ?? 'overview')
// The tab lives in the address. A value that names no tab is removed with a replacement, not a new history entry.
onMounted(() => {
  if (route.query.tab !== undefined && !TAB_IDS.includes(route.query.tab as PlayerTab)) void router.replace({ query: { ...route.query, tab: undefined } })
})
const tabs = computed(() => [
  { id: 'overview', label: t('player.tabs.overview') },
  { id: 'punishments', label: t('player.tabs.punishments') },
  { id: 'reports', label: t('player.tabs.reports') },
  { id: 'cases', label: t('player.tabs.cases') },
  { id: 'evasion', label: t('player.tabs.evasion') },
  { id: 'elo', label: t('player.tabs.elo') },
  { id: 'names', label: t('player.tabs.names') },
  { id: 'intel', label: t('player.tabs.intel') },
  { id: 'warnings', label: t('player.tabs.warnings') },
  { id: 'evidence', label: t('player.tabs.evidence') },
])
// Switching a tab replaces the address: Back leaves the player as it does without tabs.
function selectTab(tab: string): void {
  void router.replace({ query: { ...route.query, tab } })
}

// True while a child route (punish, warn, adjust, kick) is the address; only then is its outlet rendered.
const childRouteActive = computed(() => route.matched.length > 1)

const section = ref('')
const events = ref<EloEvent[] | null>(null)
const eventsLoading = ref(false)
const eventsError = ref<ApiError | null>(null)
const message = ref('')

// The UUID is not in the profile; the newest session of the player is the one place it is shown, so the
// apply sends it when that session exists. Without a session the apply goes out without it.
const playerUuid = ref<string | null>(null)
watch(
  () => props.owner,
  async (owner) => {
    const result = await api.playerSessions(owner, 0, 1)
    if (owner !== props.owner) return // the player changed while the answer was on its way
    playerUuid.value = result.ok ? (result.data.sessions[0]?.playerUuid ?? null) : null
  },
  { immediate: true },
)

// The header shows the last known name, which the name history of the player holds (kept in this session only).
watch(
  () => props.owner,
  async (owner) => {
    const result = await api.listPlayerNames(owner, 0, 50)
    if (owner !== props.owner || !result.ok || !result.data.names[0]) return
    lookup.remember(owner, result.data.names[0].name)
  },
  { immediate: true },
)

const sectionOptions = computed(() =>
  (player.data.value?.elo.sections ?? []).map((entry) => ({ value: entry.section, label: entry.displayName })),
)

async function loadEvents(): Promise<void> {
  if (!section.value) return
  const requested = section.value
  eventsLoading.value = true
  const result = await api.eloHistory(props.owner, requested)
  if (requested !== section.value) return // a newer selection superseded this answer
  eventsLoading.value = false
  if (result.ok) {
    events.value = result.data
    eventsError.value = null
  } else {
    eventsError.value = result.error
  }
}

watch(
  () => player.data.value,
  (data) => {
    if (data && !section.value) section.value = data.elo.sections[0]?.section ?? ''
  },
  { immediate: true },
)
// The ELO history is read only while its tab is open.
watch(
  () => [section.value, activeTab.value === 'elo'] as const,
  ([, onEloTab]) => {
    if (onEloTab) void loadEvents()
  },
  { immediate: true },
)

// ---- staff actions ----------------------------------------------------------------------------

/** A short id as the panel addresses it: the hash is not part of the address. */
const shortRef = (shortId: string): string => shortId.replace(/^#/, '')

const entries = computed<Punishment[]>(() => [...Object.values(player.data.value?.profile.active ?? {}), ...(player.data.value?.profile.history ?? [])])
const warnings = computed<Punishment[]>(() => (player.data.value?.profile.history ?? []).filter((entry) => entry.kind === 'WARN'))

/** Shows what a change said and refreshes everything it may have changed. The dialog closes its own route. */
async function changed(done: string): Promise<void> {
  message.value = done
  await Promise.all([player.reload(), loadEvents()])
}

// The header: the primary action is the punishment, warn and kick are in the overflow. Each is a child route: opening
// it sends nothing. A viewer gets the reason only.
const states = computed<EntityStateView[]>(() => {
  const pill = playerStatePill(Object.keys(player.data.value?.profile.active ?? {}).length)
  return [{ label: t(pill.key), tone: pill.tone, testid: 'player-state' }]
})
const primary = computed<EntityAction | null>(() =>
  session.moderator
    ? { key: 'apply', label: t('actions.apply.open'), testid: 'open-apply', to: { name: 'player-punish', params: { owner: props.owner }, query: route.query } }
    : null,
)
const overflow = computed<EntityAction[]>(() =>
  session.moderator
    ? [
        { key: 'warn', label: t('warn.open'), to: { name: 'player-warn', params: { owner: props.owner }, query: route.query } },
        { key: 'kick', label: t('kick.open'), destructive: true, to: { name: 'player-kick', params: { owner: props.owner }, query: route.query } },
      ]
    : [],
)

const context: PlayerContext = {
  owner: props.owner,
  playerUuid,
  scores: computed(() => player.data.value?.elo.sections ?? []),
  hasActivePermanent: (key) => player.data.value?.profile.active[key]?.permanent === true,
  changed,
  warned: changed,
  reload: () => player.reload(),
}
provide(PLAYER_KEY, context)
</script>

<template>
  <ResourceState
    :loading="player.loading.value"
    :error="player.error.value"
    :has-data="player.data.value !== null"
    @retry="player.reload"
  >
    <div v-if="player.data.value" class="flex flex-col gap-8">
      <EntityHeader
        :type-label="t('entity.type.player')"
        :heading="title"
        :subtitle="description"
        :states="states"
        :primary="primary"
        :overflow="overflow"
        :notice="session.moderator ? undefined : t('entity.notice.viewerPlayer')"
        :crumbs="crumbs"
      />

      <MdTabs :id-prefix="TAB_PREFIX" :label="t('player.tabs.label')" :tabs="tabs" :model-value="activeTab" @update:model-value="selectTab" />

      <div v-if="activeTab === 'overview'" :id="panelDomId(TAB_PREFIX, 'overview')" role="tabpanel" :aria-labelledby="tabDomId(TAB_PREFIX, 'overview')" class="flex flex-col gap-8">
        <ExemptionBadge :owner="owner" />

        <section aria-labelledby="scores-heading">
          <h2 id="scores-heading" class="mb-3 text-title-medium">{{ t('player.scores') }}</h2>
          <SectionScores :scores="player.data.value.elo.sections">
            <template #actions="{ entry }">
              <div v-if="session.moderator" class="mt-3 flex justify-end">
                <MdButton
                  variant="text"
                  as="link"
                  :to="{ name: 'player-adjust', params: { owner }, query: { ...route.query, section: entry.section } }"
                  :data-testid="`open-adjust-${entry.section}`"
                >
                  {{ t('actions.adjust.open') }}
                </MdButton>
              </div>
            </template>
          </SectionScores>
        </section>

        <section aria-labelledby="active-heading">
          <h2 id="active-heading" class="mb-3 text-title-medium">{{ t('player.active') }}</h2>
          <ActivePunishments :active="player.data.value.profile.active">
            <template #actions="{ section: key, punishment }">
              <div class="mt-3 flex flex-wrap justify-end gap-2">
                <MdButton
                  variant="text"
                  as="link"
                  :to="{ name: 'punished-detail', params: { shortId: shortRef(punishment.shortId) }, query: { owner } }"
                  :data-testid="`open-punished-${key}`"
                >
                  {{ t('punished.detail.open') }}
                </MdButton>
                <MdButton
                  v-if="session.moderator"
                  variant="outlined"
                  as="link"
                  :to="{ name: 'punished-revoke', params: { shortId: shortRef(punishment.shortId) }, query: { owner } }"
                  :data-testid="`open-revoke-${key}`"
                >
                  {{ t('actions.revoke.open') }}
                </MdButton>
              </div>
            </template>
          </ActivePunishments>
        </section>

        <section aria-labelledby="client-rules-heading">
          <h2 id="client-rules-heading" class="mb-3 text-title-medium">{{ t('clientRules.player.title') }}</h2>
          <ClientRuleHits :owner="owner" />
        </section>

        <PlayerSessions :owner="owner" />
      </div>

      <section v-else-if="activeTab === 'punishments'" :id="panelDomId(TAB_PREFIX, 'punishments')" role="tabpanel" :aria-labelledby="tabDomId(TAB_PREFIX, 'punishments')">
        <h2 id="history-heading" class="mb-3 text-title-medium">{{ t('player.history') }}</h2>
        <PunishmentHistory :history="player.data.value.profile.history" />
      </section>

      <div v-else-if="activeTab === 'reports'" :id="panelDomId(TAB_PREFIX, 'reports')" role="tabpanel" :aria-labelledby="tabDomId(TAB_PREFIX, 'reports')">
        <PlayerReports :owner="owner" />
      </div>

      <div v-else-if="activeTab === 'cases'" :id="panelDomId(TAB_PREFIX, 'cases')" role="tabpanel" :aria-labelledby="tabDomId(TAB_PREFIX, 'cases')">
        <PlayerCases :owner="owner" />
      </div>

      <section v-else-if="activeTab === 'evasion'" :id="panelDomId(TAB_PREFIX, 'evasion')" role="tabpanel" :aria-labelledby="tabDomId(TAB_PREFIX, 'evasion')">
        <h2 id="evasion-heading" class="mb-3 text-title-medium">{{ t('player.evasion.title') }}</h2>
        <EvasionHints :owner="owner" />
      </section>

      <section v-else-if="activeTab === 'elo'" :id="panelDomId(TAB_PREFIX, 'elo')" role="tabpanel" :aria-labelledby="tabDomId(TAB_PREFIX, 'elo')">
        <h2 id="elo-heading" class="mb-3 text-title-medium">{{ t('player.eloHistory') }}</h2>
        <EloHistory
          v-model:section="section"
          :sections="sectionOptions"
          :events="events"
          :loading="eventsLoading"
          :error="eventsError"
        />
      </section>

      <div v-else-if="activeTab === 'names'" :id="panelDomId(TAB_PREFIX, 'names')" role="tabpanel" :aria-labelledby="tabDomId(TAB_PREFIX, 'names')">
        <NameHistory :owner="owner" />
      </div>

      <section v-else-if="activeTab === 'intel'" :id="panelDomId(TAB_PREFIX, 'intel')" role="tabpanel" :aria-labelledby="tabDomId(TAB_PREFIX, 'intel')">
        <h2 id="intel-heading" class="mb-3 text-title-medium">{{ t('intel.hits.title') }}</h2>
        <IntelHitsSection :owner="owner" />
      </section>

      <section v-else-if="activeTab === 'warnings'" :id="panelDomId(TAB_PREFIX, 'warnings')" role="tabpanel" :aria-labelledby="tabDomId(TAB_PREFIX, 'warnings')">
        <h2 id="warnings-heading" class="mb-3 text-title-medium">{{ t('player.tabs.warnings') }}</h2>
        <ul v-if="warnings.length > 0" data-testid="warnings-list" class="flex flex-col gap-2">
          <li v-for="entry in warnings" :key="entry.identifier" class="text-body-large">
            <span v-if="entry.createdAt" class="text-on-surface-variant">{{ formatDateTime(entry.createdAt, locale) }}</span>
            · {{ shortRef(entry.shortId) }} · {{ entry.section }} · {{ reasonOf(entry) || t('player.noReason') }}
          </li>
        </ul>
        <p v-else class="text-body-large text-on-surface-variant">{{ t('player.warnings.empty') }}</p>
        <MdButton
          v-if="session.moderator"
          variant="tonal"
          as="link"
          class="mt-4"
          :to="{ name: 'player-warn', params: { owner }, query: route.query }"
          data-testid="open-warn-tab"
        >
          {{ t('warn.open') }}
        </MdButton>
      </section>

      <section v-else-if="activeTab === 'evidence'" :id="panelDomId(TAB_PREFIX, 'evidence')" role="tabpanel" :aria-labelledby="tabDomId(TAB_PREFIX, 'evidence')">
        <h2 id="evidence-heading" class="mb-3 text-title-medium">{{ t('player.tabs.evidence') }}</h2>
        <p class="mb-3 text-body-medium text-on-surface-variant">{{ t('player.evidence.hint') }}</p>
        <ul v-if="entries.length > 0" data-testid="evidence-list" class="flex flex-col gap-2">
          <li v-for="entry in entries" :key="entry.identifier" class="flex flex-wrap items-center gap-3">
            <RouterLink
              :to="{ name: 'punished-detail', params: { shortId: shortRef(entry.shortId) }, query: { owner, tab: 'evidence' } }"
              class="inline-flex min-h-12 items-center text-primary underline underline-offset-2 focus-ring"
            >
              {{ entry.section }} · {{ shortRef(entry.shortId) }}
            </RouterLink>
            <span class="text-body-medium text-on-surface-variant">{{ t('player.evidence.count', { count: entry.evidenceCount ?? 0 }) }}</span>
          </li>
        </ul>
        <p v-else class="text-body-large text-on-surface-variant">{{ t('player.evidence.empty') }}</p>
      </section>

      <!-- The punish, warn, adjust and kick dialogs are child routes of the player; the player stays rendered behind them. -->
      <RouterView v-if="childRouteActive" />
    </div>
  </ResourceState>
  <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
</template>
