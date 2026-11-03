<script setup lang="ts">
import { computed, provide, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterView, useRoute, useRouter } from 'vue-router'
import { useApi } from '../api/inject'
import type { ApiResult, Case, CaseStatus } from '../api/types'
import Breadcrumbs from '../components/Breadcrumbs.vue'
import PageShell from '../components/PageShell.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'
import BanIdPanel from '../components/case/BanIdPanel.vue'
import { CASE_CONTEXT_KEY, type CaseStatusApplied } from '../components/case/caseContext'
import CaseHeader from '../components/case/CaseHeader.vue'
import CaseTimeline from '../components/case/CaseTimeline.vue'
import ConversationSection from '../components/case/ConversationSection.vue'
import { punishmentStateTone } from '../components/case/caseTones'
import ProtocolSection from '../components/case/ProtocolSection.vue'
import NoteEditor from '../components/case/NoteEditor.vue'
import RelatedCases from '../components/case/RelatedCases.vue'
import TranscriptExport from '../components/case/TranscriptExport.vue'
import EntityHeader from '../components/entity/EntityHeader.vue'
import type { EntityAction, EntityAssignee, EntityStateView } from '../components/entity/entityHeader'
import { caseStatePill } from '../components/entity/entityState'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import MdTabs from '../components/md/MdTabs.vue'
import { panelDomId, tabDomId } from '../components/md/tabs'
import ResourceState from '../components/ResourceState.vue'
import { useResource } from '../composables/useResource'
import { withWorkspace } from '../nav/workspaces'
import { useSessionStore } from '../stores/session'

const props = defineProps<{ reference: string }>()

/** How long a close can be undone from the message under the header. */
const UNDO_WINDOW_MS = 10_000

const TAB_IDS = ['overview', 'conversation', 'audit'] as const
type CaseTab = (typeof TAB_IDS)[number]
/** The ids the tabs and their panels share: the tab strip's tab and panel ids are derived from these. */
const TAB_PREFIX = 'case'

const { t } = useI18n()
const api = useApi()
const session = useSessionStore()
const route = useRoute()
const router = useRouter()

const detail = useResource(() => api.caseDetail(props.reference))

// The page title is the case itself; the trail back to the list is the breadcrumbs.
const title = computed(() => (detail.data.value ? t('case.header.heading', { id: detail.data.value.shortId }) : t('case.title')))
// The workspace leads the trail for an administrator (the switcher is shown to them only).
const trail = (items: { label: string; to?: string }[]) => withWorkspace(items, { show: session.administrator, workspace: 'moderation', translate: (key) => t(key) })
const crumbs = computed(() => [
  { label: t('nav.cases'), to: '/cases' },
  { label: detail.data.value?.shortId ?? props.reference },
])
const timeline = ref<InstanceType<typeof CaseTimeline> | null>(null)
const conversation = ref<InstanceType<typeof ConversationSection> | null>(null)
const message = ref('')
/** The last close, undone within the window: the version the close left and the status the case had before it. */
const undo = ref<{ version: number; status: CaseStatus } | null>(null)
/** A new message or undo offer restarts the snackbar, so its window starts when it is shown. */
const snackbarKey = ref(0)
watch([message, undo], () => (snackbarKey.value += 1))

// The tab lives in the URL, so a link and a reload keep it. An unknown or missing value is the overview.
const activeTab = computed<CaseTab>(() => TAB_IDS.find((tab) => tab === route.query.tab) ?? 'overview')

/** Messages that arrived while another tab was open. The first load of the thread is not new. */
const unseen = ref(0)
let threadCount: number | null = null

const tabs = computed(() => [
  { id: 'overview', label: t('case.tabs.overview') },
  { id: 'conversation', label: t('case.tabs.conversation'), badge: unseen.value, badgeLabel: t('case.tabs.unseen', { n: unseen.value }, unseen.value) },
  { id: 'audit', label: t('case.tabs.audit') },
])

// replace, not push: Back leaves the case page as it does without tabs.
function selectTab(tab: string): void {
  void router.replace({ query: { ...route.query, tab } })
}

function threadCounted(count: number): void {
  const previous = threadCount
  threadCount = count
  if (previous !== null && count > previous && activeTab.value !== 'conversation') unseen.value += count - previous
}

watch(activeTab, (tab) => {
  if (tab === 'conversation') unseen.value = 0
})

watch(
  () => props.reference,
  () => {
    threadCount = null
    unseen.value = 0
    void detail.reload()
  },
)

function updated(kase: Case): void {
  detail.data.value = kase
  void timeline.value?.reload()
}

/** A close or reopen confirmed in its dialog: the case is shown as the server has it, a close can be undone. */
function statusApplied({ kase, previous, action }: CaseStatusApplied): void {
  updated(kase)
  undo.value = action === 'close' ? { version: kase.version, status: previous } : null
  message.value = t(action === 'close' ? 'case.bar.closed' : 'case.bar.reopened')
}

provide(CASE_CONTEXT_KEY, { kase: detail.data, applied: statusApplied })

/** Undo sends the status back with the version the close left, so a change made since is refused, not overwritten. */
async function undoClose(): Promise<void> {
  const offer = undo.value
  const kase = detail.data.value
  if (!offer || !kase) return
  undo.value = null
  const result = await api.updateCase(kase.punishmentIdentifier, { expectedVersion: offer.version, status: offer.status })
  if (result.ok) {
    updated(result.data)
    message.value = t('entity.undo.undone')
  } else {
    message.value = result.error.status === 409 ? t('entity.undo.changed') : result.error.message
  }
}

function dismissSnackbar(): void {
  undo.value = null
  message.value = ''
}

/** True while a child route (a close or reopen confirmation) is the address; only then is the outlet rendered. */
const childRouteActive = computed(() => route.matched.length > 1)

const caseStates = computed<EntityStateView[]>(() => {
  const kase = detail.data.value
  if (!kase) return []
  const pill = caseStatePill(kase.status)
  return [
    { label: t(pill.key), tone: pill.tone, testid: 'case-status' },
    { label: t(`case.state.${kase.punishmentState}`), tone: punishmentStateTone(kase.punishmentState), testid: 'punishment-state' },
    ...(kase.permanent ? [{ label: t('player.permanent'), tone: 'error' as const }] : []),
  ]
})

const assignee = computed<EntityAssignee | undefined>(() => {
  const kase = detail.data.value
  if (!kase) return undefined
  if (kase.assigneeName) return { label: kase.assigneeName, unassigned: false }
  if (kase.assigneeOid) return { label: `${kase.assigneeOid.slice(0, 8)}…`, unassigned: false }
  return { label: t('entity.unassigned'), unassigned: true }
})

// The next step of the case is one action; it opens its confirmation route (a child route, nothing is sent yet).
const primary = computed<EntityAction | null>(() => {
  const kase = detail.data.value
  if (!kase || !session.moderator) return null
  const query = route.query
  return kase.status === 'CLOSED'
    ? { key: 'reopen', label: t('case.bar.reopen'), testid: 'reopen-case', to: { name: 'case-reopen', params: { reference: props.reference }, query } }
    : { key: 'close', label: t('case.bar.close'), testid: 'close-case', to: { name: 'case-close', params: { reference: props.reference }, query } }
})

/** The conversation reloads itself; the timeline shows the same events as lifecycle entries. */
async function conversationChanged(text: string): Promise<void> {
  message.value = text
  await timeline.value?.reload()
}

async function reload(): Promise<void> {
  await Promise.all([detail.reload(), timeline.value?.reload(), conversation.value?.reload()])
}

/** A conversation or Ban ID action finished: show its message, refresh what it may have changed. */
async function acted(text: string): Promise<void> {
  message.value = text
  await reload()
}

async function addNote(text: string): Promise<ApiResult<unknown>> {
  const result = await api.addCaseNote(detail.data.value?.punishmentIdentifier ?? props.reference, text)
  if (result.ok) {
    message.value = t('case.notes.saved')
    await timeline.value?.reload()
  }
  return result
}
</script>

<template>
  <PageShell :title="title">
    <template #header>
      <EntityHeader
        v-if="detail.data.value"
        :type-label="t('entity.type.case')"
        :heading="title"
        :short-id="detail.data.value.shortId"
        :states="caseStates"
        :owner="{ label: `${detail.data.value.ownerPrefix}…` }"
        :assignee="assignee"
        :primary="primary"
        :notice="session.moderator ? undefined : t('entity.notice.viewerCase')"
        :crumbs="trail(crumbs)"
      />
      <header v-else class="flex flex-col gap-2">
        <Breadcrumbs :items="trail(crumbs)" />
        <h1 class="text-headline-small text-on-surface">{{ title }}</h1>
      </header>
    </template>

    <SkeletonBlock v-if="detail.loading.value && detail.data.value === null" height="12rem" />

    <ResourceState :loading="detail.loading.value" :error="detail.error.value" :has-data="detail.data.value !== null" @retry="detail.reload">
      <div v-if="detail.data.value" class="flex flex-col gap-6">
        <MdTabs :id-prefix="TAB_PREFIX" :label="t('case.tabs.label')" :tabs="tabs" :model-value="activeTab" @update:model-value="selectTab" />

        <div v-show="activeTab === 'overview'" :id="panelDomId(TAB_PREFIX, 'overview')" role="tabpanel" :aria-labelledby="tabDomId(TAB_PREFIX, 'overview')" class="flex flex-col gap-6">
          <CaseHeader :kase="detail.data.value" @updated="updated" @reload="reload" @message="message = $event" />

          <BanIdPanel :kase="detail.data.value" @changed="acted" />

          <RelatedCases :related="detail.data.value.related" />
        </div>

        <div
          v-show="activeTab === 'conversation'"
          :id="panelDomId(TAB_PREFIX, 'conversation')"
          role="tabpanel"
          :aria-labelledby="tabDomId(TAB_PREFIX, 'conversation')"
        >
          <ConversationSection
            ref="conversation"
            :kase="detail.data.value"
            :active="activeTab === 'conversation'"
            @changed="conversationChanged"
            @messages="threadCounted"
          />
        </div>

        <div v-show="activeTab === 'audit'" :id="panelDomId(TAB_PREFIX, 'audit')" role="tabpanel" :aria-labelledby="tabDomId(TAB_PREFIX, 'audit')" class="flex flex-col gap-6">
          <section :aria-label="t('case.notes.title')" class="flex flex-col gap-3">
            <h2 class="text-title-medium">{{ t('case.notes.title') }}</h2>
            <NoteEditor v-if="session.moderator" :label="t('case.notes.newLabel')" :submit-label="t('case.notes.add')" :save="addNote" />
            <p v-else class="text-body-medium text-on-surface-variant" data-testid="notes-read-only">{{ t('case.notes.readOnly') }}</p>
          </section>

          <CaseTimeline
            ref="timeline"
            :reference="detail.data.value.punishmentIdentifier"
            @changed="message = $event"
          />

          <ProtocolSection :reference="detail.data.value.punishmentIdentifier" :short-id="detail.data.value.shortId" />

          <TranscriptExport
            v-if="session.administrator"
            :reference="detail.data.value.punishmentIdentifier"
            :short-id="detail.data.value.shortId"
            @exported="acted"
          />
        </div>

        <!-- The close and reopen confirmations are child routes of the case; the case stays rendered behind them. -->
        <RouterView v-if="childRouteActive" />
      </div>
    </ResourceState>

    <MdSnackbar
      :key="snackbarKey"
      :open="undo !== null || message !== ''"
      :message="message"
      :action-label="undo ? t('entity.undo.action') : undefined"
      :auto-close-ms="undo ? UNDO_WINDOW_MS : undefined"
      @action="undoClose"
      @close="dismissSnackbar"
    />
  </PageShell>
</template>
