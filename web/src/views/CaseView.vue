<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useApi } from '../api/inject'
import type { ApiResult, Case } from '../api/types'
import Breadcrumbs from '../components/Breadcrumbs.vue'
import PageShell from '../components/PageShell.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'
import BanIdPanel from '../components/case/BanIdPanel.vue'
import CaseBar from '../components/case/CaseBar.vue'
import CaseHeader from '../components/case/CaseHeader.vue'
import CaseTimeline from '../components/case/CaseTimeline.vue'
import ConversationSection from '../components/case/ConversationSection.vue'
import ProtocolSection from '../components/case/ProtocolSection.vue'
import NoteEditor from '../components/case/NoteEditor.vue'
import RelatedCases from '../components/case/RelatedCases.vue'
import TranscriptExport from '../components/case/TranscriptExport.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import MdTabs from '../components/md/MdTabs.vue'
import { panelDomId, tabDomId } from '../components/md/tabs'
import ResourceState from '../components/ResourceState.vue'
import { useResource } from '../composables/useResource'
import { withWorkspace } from '../nav/workspaces'
import { useSessionStore } from '../stores/session'

const props = defineProps<{ reference: string }>()

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
    <template #breadcrumbs>
      <Breadcrumbs :items="trail(crumbs)" />
    </template>

    <SkeletonBlock v-if="detail.loading.value && detail.data.value === null" height="12rem" />

    <ResourceState :loading="detail.loading.value" :error="detail.error.value" :has-data="detail.data.value !== null" @retry="detail.reload">
      <div v-if="detail.data.value" class="flex flex-col gap-6">
        <CaseBar :kase="detail.data.value" @updated="updated" @reload="reload" @message="message = $event" />

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
      </div>
    </ResourceState>

    <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
  </PageShell>
</template>
