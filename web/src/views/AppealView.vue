<script setup lang="ts">
// One appeal, addressed by its identifier: the statement, the punishment it contests, the conversation and the decision.
// The decision is the child route `/appeals/:id/decide`, opened over this page. Nothing here changes state on its own.
import { computed, provide, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterView, useRoute, useRouter } from 'vue-router'
import { useApi } from '../api/inject'
import type { Appeal, AppealStatus } from '../api/types'
import Breadcrumbs from '../components/Breadcrumbs.vue'
import DefinitionList from '../components/DefinitionList.vue'
import PageShell from '../components/PageShell.vue'
import ResourceState from '../components/ResourceState.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'
import StatusPill from '../components/StatusPill.vue'
import TextLink from '../components/TextLink.vue'
import AppealHeader from '../components/appeals/AppealHeader.vue'
import { APPEAL_CONTEXT, DECIDABLE_STATUSES } from '../components/appeals/appealContext'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import MdTabs from '../components/md/MdTabs.vue'
import { panelDomId, tabDomId } from '../components/md/tabs'
import { useResource } from '../composables/useResource'
import { formatDateTime, shortHash } from '../format'
import { withWorkspace } from '../nav/workspaces'
import { useSessionStore } from '../stores/session'

const props = defineProps<{ id: string }>()

const TAB_IDS = ['conversation', 'decision', 'case'] as const
type AppealTab = (typeof TAB_IDS)[number]
/** The ids the tabs and their panels share: the tab strip's tab and panel ids are derived from these. */
const TAB_PREFIX = 'appeal'
const PENDING: AppealStatus[] = ['SUBMITTED', 'ELIGIBLE_PENDING_REVIEW', 'IN_REVIEW']

const { t, locale } = useI18n()
const api = useApi()
const session = useSessionStore()
const route = useRoute()
const router = useRouter()

const appeal = useResource(() => api.appeal(props.id))
const current = computed<Appeal | null>(() => appeal.data.value)
const notFound = computed(() => appeal.error.value?.kind === 'NOT_FOUND')
const pending = computed(() => current.value !== null && PENDING.includes(current.value.status))
const canDecide = computed(() => session.moderator && current.value !== null && (DECIDABLE_STATUSES as readonly string[]).includes(current.value.status))

// The punishment this appeal contests. Its case and its conversation are read once the appeal is known.
const punishment = computed(() => current.value?.punishmentIdentifier ?? '')
const kase = useResource(() => api.caseDetail(punishment.value), { immediate: false })
const last = useResource(() => api.caseTimeline(punishment.value, { types: ['message.added'], order: 'desc', size: 1 }), { immediate: false })
const lastMessage = computed(() => last.data.value?.items[0] ?? null)

const message = ref('')

// The tab lives in the URL, so a link and a reload keep it. An unknown or missing value is the decision tab.
const activeTab = computed<AppealTab>(() => TAB_IDS.find((tab) => tab === route.query.tab) ?? 'decision')
const tabs = computed(() => [
  { id: 'conversation', label: t('appeal.tabs.conversation') },
  { id: 'decision', label: t('appeal.tabs.decision') },
  { id: 'case', label: t('appeal.tabs.case') },
])

// The title of the page is the appeal; the trail back to the list is the breadcrumbs.
const crumbs = computed(() => [{ label: t('nav.appeals'), to: '/appeals' }, { label: `#${props.id.slice(0, 8)}` }])
// The workspace leads the trail for an administrator (the switcher is shown to them only).
const trail = (items: { label: string; to?: string }[]) => withWorkspace(items, { show: session.administrator, workspace: 'moderation', translate: (key) => t(key) })

const summaryItems = computed(() => {
  const loaded = current.value
  if (!loaded) return []
  return [
    { key: 'submitted', term: t('appeal.summary.submitted'), value: formatDateTime(loaded.createdAt, locale.value) },
    { key: 'statement', term: t('appeal.summary.statement'), value: loaded.statement },
    { key: 'evidence', term: t('appeal.summary.evidence'), value: String(loaded.evidenceCount) },
    { key: 'appellant', term: t('appeal.summary.appellant'), value: shortHash(loaded.appellantHash) },
  ]
})

function selectTab(tab: string): void {
  void router.replace({ query: { ...route.query, tab } })
}

// Opens the decision child route over this page; the dialog itself sends nothing until it is confirmed.
function decide(): void {
  void router.push({ name: 'appeal-decide', params: { id: props.id }, query: route.query })
}

watch(
  () => props.id,
  () => void appeal.reload(),
)

// The case and the last message load once the punishment is known; a failure in one never hides the appeal.
watch(
  punishment,
  (identifier) => {
    if (!identifier) return
    void kase.reload()
    void last.reload()
  },
  { immediate: true },
)

provide(APPEAL_CONTEXT, {
  appeal: current,
  reload: () => appeal.reload(),
  announce: (text) => {
    message.value = text
  },
})
</script>

<template>
  <PageShell :title="t('appeal.title')">
    <template #breadcrumbs>
      <Breadcrumbs :items="trail(crumbs)" />
    </template>

    <div v-if="notFound" class="mx-auto flex max-w-xl flex-col gap-3" data-testid="appeal-not-found">
      <p class="text-body-large">{{ t('appeal.notFound') }}</p>
      <TextLink to="/appeals" data-testid="appeal-back">{{ t('appeal.back') }}</TextLink>
    </div>

    <template v-else>
      <SkeletonBlock v-if="appeal.loading.value && current === null" height="12rem" />

      <ResourceState :loading="appeal.loading.value" :error="appeal.error.value" :has-data="current !== null" @retry="appeal.reload">
        <div v-if="current" class="flex flex-col gap-6">
          <AppealHeader :appeal="current" :can-decide="canDecide" @decide="decide" />

          <DefinitionList data-testid="summary" :items="summaryItems">
            <template #value="{ item }">
              <TextLink v-if="item.key === 'appellant'" :to="`/players/${current.appellantHash}`">{{ item.value }}</TextLink>
              <template v-else>{{ item.value }}</template>
            </template>
          </DefinitionList>

          <MdTabs :id-prefix="TAB_PREFIX" :label="t('appeal.tabs.label')" :tabs="tabs" :model-value="activeTab" @update:model-value="selectTab" />

          <div
            v-show="activeTab === 'conversation'"
            :id="panelDomId(TAB_PREFIX, 'conversation')"
            role="tabpanel"
            :aria-labelledby="tabDomId(TAB_PREFIX, 'conversation')"
            data-testid="conversation-panel"
            class="flex flex-col gap-3"
          >
            <p v-if="last.error.value" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container" data-testid="conversation-failed">
              {{ t('appeal.conversation.failed') }}
            </p>
            <p v-else-if="last.loading.value && last.data.value === null" class="text-body-large text-on-surface-variant">{{ t('state.loading') }}</p>
            <p v-else-if="lastMessage === null" class="text-body-large text-on-surface-variant" data-testid="conversation-state">
              {{ t('appeal.conversation.none') }}
            </p>
            <p v-else-if="lastMessage.actor.trust === 'PLAYER'" class="text-body-large" data-testid="awaiting-answer">
              {{ t('appeal.conversation.playerLast') }}
            </p>
            <p v-else class="text-body-large text-on-surface-variant" data-testid="conversation-state">{{ t('appeal.conversation.staffLast') }}</p>
            <TextLink :to="`/cases/${punishment}?tab=conversation`" data-testid="open-conversation">{{ t('appeal.conversation.open') }}</TextLink>
          </div>

          <div
            v-show="activeTab === 'decision'"
            :id="panelDomId(TAB_PREFIX, 'decision')"
            role="tabpanel"
            :aria-labelledby="tabDomId(TAB_PREFIX, 'decision')"
            data-testid="decision-panel"
            class="flex flex-col gap-3"
          >
            <div class="flex flex-wrap items-center gap-3">
              <StatusPill :label="t(`appeals.status.${current.status}`)" :tone="pending ? 'tertiary' : 'neutral'" />
            </div>
            <template v-if="pending">
              <p class="text-body-large">{{ t('appeal.decision.waiting') }}</p>
              <p v-if="!session.moderator" class="text-body-medium text-on-surface-variant" data-testid="read-only">{{ t('appeal.decision.readOnly') }}</p>
            </template>
            <template v-else>
              <p class="text-body-large" data-testid="decision-note">{{ current.decisionNote || t('appeal.decision.noNote') }}</p>
              <p class="text-body-medium text-on-surface-variant">{{ t('appeal.decision.updated', { when: formatDateTime(current.updatedAt, locale) }) }}</p>
            </template>
          </div>

          <div
            v-show="activeTab === 'case'"
            :id="panelDomId(TAB_PREFIX, 'case')"
            role="tabpanel"
            :aria-labelledby="tabDomId(TAB_PREFIX, 'case')"
            data-testid="case-panel"
            class="flex flex-col gap-3"
          >
            <ResourceState :loading="kase.loading.value" :error="kase.error.value" :has-data="kase.data.value !== null" @retry="kase.reload">
              <p v-if="kase.data.value" class="flex flex-wrap items-center gap-3">
                <TextLink :to="`/cases/${punishment}`" data-testid="case-link">{{ t('appeal.case.open', { id: kase.data.value.shortId }) }}</TextLink>
                <span class="text-body-medium text-on-surface-variant">{{ t('appeal.case.hint') }}</span>
              </p>
            </ResourceState>
          </div>

          <RouterView />
        </div>
      </ResourceState>
    </template>

    <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
  </PageShell>
</template>
