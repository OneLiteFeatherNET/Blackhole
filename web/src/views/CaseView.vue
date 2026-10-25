<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../api/inject'
import type { ApiResult, Case } from '../api/types'
import Breadcrumbs from '../components/Breadcrumbs.vue'
import PageShell from '../components/PageShell.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'
import BanIdPanel from '../components/case/BanIdPanel.vue'
import CaseHeader from '../components/case/CaseHeader.vue'
import CaseTimeline from '../components/case/CaseTimeline.vue'
import ConversationSection from '../components/case/ConversationSection.vue'
import ProtocolSection from '../components/case/ProtocolSection.vue'
import NoteEditor from '../components/case/NoteEditor.vue'
import RelatedCases from '../components/case/RelatedCases.vue'
import TranscriptExport from '../components/case/TranscriptExport.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import ResourceState from '../components/ResourceState.vue'
import { useResource } from '../composables/useResource'
import { withWorkspace } from '../nav/workspaces'
import { useSessionStore } from '../stores/session'

const props = defineProps<{ reference: string }>()

const { t } = useI18n()
const api = useApi()
const session = useSessionStore()

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

watch(
  () => props.reference,
  () => void detail.reload(),
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
        <CaseHeader :kase="detail.data.value" @updated="updated" @reload="reload" @message="message = $event" />

        <BanIdPanel :kase="detail.data.value" @changed="acted" />

        <ConversationSection ref="conversation" :kase="detail.data.value" @changed="conversationChanged" />

        <ProtocolSection :reference="detail.data.value.punishmentIdentifier" :short-id="detail.data.value.shortId" />

        <TranscriptExport
          v-if="session.administrator"
          :reference="detail.data.value.punishmentIdentifier"
          :short-id="detail.data.value.shortId"
          @exported="acted"
        />

        <section :aria-label="t('case.notes.title')" class="flex flex-col gap-3">
          <h2 class="text-title-medium">{{ t('case.notes.title') }}</h2>
          <NoteEditor v-if="session.moderator" :label="t('case.notes.newLabel')" :submit-label="t('case.notes.add')" :save="addNote" />
          <p v-else class="text-body-medium text-on-surface-variant" data-testid="notes-read-only">{{ t('case.notes.readOnly') }}</p>
        </section>

        <RelatedCases :related="detail.data.value.related" />

        <CaseTimeline
          ref="timeline"
          :reference="detail.data.value.punishmentIdentifier"
          @changed="message = $event"
        />
      </div>
    </ResourceState>

    <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
  </PageShell>
</template>
