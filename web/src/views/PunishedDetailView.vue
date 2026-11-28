<script setup lang="ts">
// One punishment, addressed by its short id: `/punished/:shortId?tab=details|evidence|history&owner=<hash>`. The short id
// finds the punishment; the owner (the player's hash, which staff already see) is needed for the history and the revoke.
// The Ban ID is never part of this address.
import { computed, provide, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterView, useRoute, useRouter } from 'vue-router'
import { useApi } from '../api/inject'
import type { Punishment } from '../api/types'
import Breadcrumbs from '../components/Breadcrumbs.vue'
import CopyLink from '../components/CopyLink.vue'
import EvidencePanel from '../components/evidence/EvidencePanel.vue'
import MdButton from '../components/md/MdButton.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import MdTabs from '../components/md/MdTabs.vue'
import { panelDomId, tabDomId } from '../components/md/tabs'
import PageShell from '../components/PageShell.vue'
import ResourceState from '../components/ResourceState.vue'
import PunishmentHistory from '../components/player/PunishmentHistory.vue'
import { expiresAt, reasonOf } from '../components/player/punishmentInfo'
import { PUNISHED_KEY, type PunishedContext } from '../components/punished/punishedContext'
import { useResource } from '../composables/useResource'
import { formatDateTime } from '../format'
import { withWorkspace } from '../nav/workspaces'
import { useSessionStore } from '../stores/session'

const props = defineProps<{ shortId: string }>()

const TAB_IDS = ['details', 'evidence', 'history'] as const
type PunishedTab = (typeof TAB_IDS)[number]
const TAB_PREFIX = 'punished'
const SHORT_ID = /^[0-9A-F]{8}$/i
const OWNER_HASH = /^[0-9a-f]{128}$/i

const { t, locale } = useI18n()
const api = useApi()
const session = useSessionStore()
const route = useRoute()
const router = useRouter()

const short = computed(() => props.shortId.replace(/^#/, ''))
const validShort = computed(() => SHORT_ID.test(short.value))
const owner = computed(() => {
  const value = route.query.owner
  return typeof value === 'string' && OWNER_HASH.test(value) ? value : null
})

// A value that is not a short id is not looked up at all: nothing is requested for it.
const found = useResource(async () => (validShort.value ? api.punishmentsByShortId(short.value) : { ok: true as const, data: [] as Punishment[] }))
const matches = computed<Punishment[]>(() => found.data.value ?? [])
const entry = computed<Punishment | null>(() => (matches.value.length === 1 ? matches.value[0]! : null))
const inForce = computed(() => entry.value !== null && !entry.value.lifted)

const activeTab = computed<PunishedTab>(() => TAB_IDS.find((tab) => tab === route.query.tab) ?? 'details')
const tabs = computed(() => [
  { id: 'details', label: t('punished.detail.tabs.details') },
  { id: 'evidence', label: t('punished.detail.tabs.evidence') },
  { id: 'history', label: t('punished.detail.tabs.history') },
])

// The history of the player is one read, made only for the history tab and only when the owner is in the address.
const history = useResource(() => api.profile(owner.value ?? ''), { immediate: false })
watch(
  [activeTab, owner],
  ([tab, who]) => {
    if (tab === 'history' && who !== null) void history.reload()
  },
  { immediate: true },
)

const childRouteActive = computed(() => route.matched.length > 1)
const message = ref('')

const title = computed(() => t('punished.detail.heading', { id: `#${short.value}` }))
const trail = (items: { label: string; to?: string }[]) => withWorkspace(items, { show: session.administrator, workspace: 'moderation', translate: (key) => t(key) })
const crumbs = computed(() => [{ label: t('punished.title'), to: '/punished' }, { label: `#${short.value}` }])

/** Reloads the entry after a revoke and shows what happened. */
async function changed(done: string): Promise<void> {
  message.value = done
  await found.reload()
}
provide<PunishedContext>(PUNISHED_KEY, { changed })
// The revoke route reads this page's context; the page owns the entry it changes.

// Switching a tab replaces the address, as on the player page.
function selectTab(tab: string): void {
  void router.replace({ query: { ...route.query, tab } })
}
</script>

<template>
  <PageShell :title="title">
    <template #breadcrumbs>
      <Breadcrumbs :items="trail(crumbs)" />
    </template>
    <template #actions>
      <CopyLink />
    </template>

    <ResourceState :loading="found.loading.value" :error="found.error.value" :has-data="found.data.value !== null" @retry="found.reload">
      <p v-if="!validShort || matches.length === 0" class="text-body-large">{{ t('punishedDetail.notFound') }}</p>
      <p v-else-if="matches.length > 1" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">
        {{ t('punishedDetail.ambiguous') }}
      </p>

      <template v-else-if="entry">
        <div data-testid="punished-detail" class="flex flex-col gap-6">
          <MdTabs :id-prefix="TAB_PREFIX" :label="t('punished.detail.tabs.label')" :tabs="tabs" :model-value="activeTab" @update:model-value="selectTab" />

          <section v-if="activeTab === 'details'" :id="panelDomId(TAB_PREFIX, 'details')" role="tabpanel" :aria-labelledby="tabDomId(TAB_PREFIX, 'details')" class="flex flex-col gap-4">
            <dl class="grid grid-cols-[auto_1fr] gap-x-6 gap-y-2 text-body-large">
              <dt class="text-on-surface-variant">{{ t('punished.detail.section') }}</dt>
              <dd>{{ entry.section }}</dd>
              <dt class="text-on-surface-variant">{{ t('punished.detail.reason') }}</dt>
              <dd>{{ reasonOf(entry) || t('punished.noReason') }}</dd>
              <dt class="text-on-surface-variant">{{ t('punished.detail.term') }}</dt>
              <dd>
                {{ entry.permanent ? t('player.permanent') : t('player.temporary') }}
                <template v-if="expiresAt(entry) !== null">· {{ t('player.until') }} {{ formatDateTime(expiresAt(entry) as number, locale) }}</template>
              </dd>
              <dt class="text-on-surface-variant">{{ t('punished.detail.since') }}</dt>
              <dd>{{ entry.createdAt ? formatDateTime(entry.createdAt, locale) : '—' }}</dd>
              <dt class="text-on-surface-variant">{{ t('punished.detail.state') }}</dt>
              <dd>{{ inForce ? t('punished.detail.inForce') : t('punished.detail.lifted') }}</dd>
            </dl>
            <div class="flex flex-wrap gap-2">
              <MdButton variant="tonal" as="link" :to="{ name: 'case', params: { reference: entry.identifier } }" data-testid="open-case">
                {{ t('case.open') }}
              </MdButton>
              <MdButton
                v-if="session.moderator && inForce && owner !== null"
                variant="outlined"
                as="link"
                :to="{ name: 'punished-revoke', params: { shortId: short }, query: { owner } }"
                data-testid="open-revoke"
              >
                {{ t('actions.revoke.open') }}
              </MdButton>
            </div>
            <p v-if="inForce && owner === null && session.moderator" class="text-body-medium text-on-surface-variant">{{ t('punishedDetail.noOwner') }}</p>
          </section>

          <section v-else-if="activeTab === 'evidence'" :id="panelDomId(TAB_PREFIX, 'evidence')" role="tabpanel" :aria-labelledby="tabDomId(TAB_PREFIX, 'evidence')">
            <EvidencePanel :punishment="entry.identifier" :readonly="!session.moderator" />
          </section>

          <section v-else :id="panelDomId(TAB_PREFIX, 'history')" role="tabpanel" :aria-labelledby="tabDomId(TAB_PREFIX, 'history')">
            <p v-if="owner === null" class="text-body-large text-on-surface-variant">{{ t('punishedDetail.historyNeedsOwner') }}</p>
            <ResourceState v-else :loading="history.loading.value" :error="history.error.value" :has-data="history.data.value !== null" @retry="history.reload">
              <PunishmentHistory :history="history.data.value?.history ?? []" />
            </ResourceState>
          </section>
        </div>
      </template>
    </ResourceState>

    <RouterView v-if="childRouteActive" />
    <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
  </PageShell>
</template>
