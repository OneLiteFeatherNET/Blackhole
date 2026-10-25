<script setup lang="ts">
// One report with everything needed to judge it: who reported whom and why, the evidence (including the
// chat excerpt), the other reports against the same player and the player's active punishments. A
// moderator resolves an open report here: "Apply template" actions it with a punishment, "Dismiss" closes
// it with a note. Both use the resolve route. The reporter is shown by hash only, never by name or UUID.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../api/inject'
import type { Evidence, Punishment, Report, ReportStatus } from '../api/types'
import Breadcrumbs from '../components/Breadcrumbs.vue'
import DefinitionList from '../components/DefinitionList.vue'
import PageShell from '../components/PageShell.vue'
import SkeletonBlock from '../components/SkeletonBlock.vue'
import TextLink from '../components/TextLink.vue'
import DeleteEvidenceDialog from '../components/evidence/DeleteEvidenceDialog.vue'
import EvidenceList from '../components/evidence/EvidenceList.vue'
import MdButton from '../components/md/MdButton.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import ActivePunishments from '../components/player/ActivePunishments.vue'
import ApplyTemplateDialog from '../components/player/ApplyTemplateDialog.vue'
import ReportDismissDialog from '../components/reports/ReportDismissDialog.vue'
import ResourceState from '../components/ResourceState.vue'
import StatusPill from '../components/StatusPill.vue'
import { useResource } from '../composables/useResource'
import { formatDateTime, shortHash } from '../format'
import { useLookupStore } from '../stores/lookup'
import { withWorkspace } from '../nav/workspaces'
import { useSessionStore } from '../stores/session'

const OPEN: ReportStatus[] = ['OPEN', 'UNDER_REVIEW']

const props = defineProps<{ id: string }>()

const { t, locale } = useI18n()
const api = useApi()
const session = useSessionStore()
const lookup = useLookupStore()

const report = useResource(() => api.report(props.id))
const others = ref<Report[]>([])
const active = ref<Record<string, Punishment>>({})
const evidence = ref<Evidence[]>([])
const missingEvidence = ref(0)
/** Which of the side sections could not be loaded; the report itself is still shown. */
const failed = ref<Array<'others' | 'active' | 'evidence'>>([])

const applying = ref(false)
const dismissing = ref(false)
const deleting = ref<Evidence | null>(null)
const message = ref('')

const current = computed(() => report.data.value)
const isOpen = computed(() => OPEN.includes(current.value?.status ?? 'OPEN'))
const canResolve = computed(() => session.moderator && isOpen.value && Boolean(current.value?.identifier))
const playerName = computed(() => (current.value ? lookup.nameOf(current.value.reportedHash) : null))

// The trail back to the list is the breadcrumbs; the title of the page is the report itself.
const crumbs = computed(() => [{ label: t('nav.reports'), to: '/reports' }, { label: `#${props.id.slice(0, 8)}` }])
// The workspace leads the trail for an administrator (the switcher is shown to them only).
const trail = (items: { label: string; to?: string }[]) => withWorkspace(items, { show: session.administrator, workspace: 'moderation', translate: (key) => t(key) })

const summaryItems = computed(() => {
  const loaded = current.value
  if (!loaded) return []
  return [
    { key: 'reported', term: t('reports.detail.reportedPlayer'), value: playerName.value ?? shortHash(loaded.reportedHash) },
    { key: 'reporter', term: t('reports.detail.reporter'), value: shortHash(loaded.reporterHash) },
    { key: 'category', term: t('reports.columns.category'), value: t(`reports.category.${loaded.category}`) },
    { key: 'reason', term: t('reports.detail.reason'), value: loaded.description || t('reports.detail.noReason'), testid: 'reason' },
    { key: 'when', term: t('reports.columns.when'), value: formatDateTime(loaded.createdAt, locale.value) },
    ...(isOpen.value
      ? []
      : [{ key: 'resolution', term: t('reports.detail.resolutionNote'), value: loaded.resolutionNote || t('reports.detail.noNote'), testid: 'resolution-note' }]),
  ]
})

watch(
  () => props.id,
  () => void report.reload(),
)

// The side sections load once the report is known; a failure in one never hides the others.
watch(
  () => report.data.value,
  async (loaded) => {
    if (!loaded) return
    const references = loaded.evidenceReferences ?? []
    const [againstResult, profileResult, ...items] = await Promise.all([
      api.reportsAgainst(loaded.reportedHash),
      api.profile(loaded.reportedHash),
      ...references.map((reference) => api.evidenceItem(reference)),
    ])
    const problems: Array<'others' | 'active' | 'evidence'> = []
    if (againstResult.ok) others.value = againstResult.data.filter((entry) => entry.identifier !== loaded.identifier)
    else problems.push('others')
    if (profileResult.ok) active.value = profileResult.data.active
    else problems.push('active')
    const found = items.flatMap((item) => (item.ok ? [item.data] : []))
    evidence.value = found
    missingEvidence.value = items.length - found.length
    if (missingEvidence.value > 0) problems.push('evidence')
    failed.value = problems
  },
  { immediate: true },
)

async function done(text: string): Promise<void> {
  applying.value = false
  dismissing.value = false
  deleting.value = null
  message.value = text
  await report.reload()
}
</script>

<template>
  <PageShell :title="t('reports.detail.title')">
    <template #breadcrumbs>
      <Breadcrumbs :items="trail(crumbs)" />
    </template>

    <SkeletonBlock v-if="report.loading.value && report.data.value === null" height="12rem" />

    <ResourceState :loading="report.loading.value" :error="report.error.value" :has-data="current !== null" @retry="report.reload">
      <div v-if="current" class="flex flex-col gap-8">
        <div>
          <StatusPill :label="t(`reports.status.${current.status ?? 'OPEN'}`)" :tone="isOpen ? 'tertiary' : 'neutral'" data-testid="status" />
        </div>

        <section aria-labelledby="report-summary" class="flex flex-col gap-3">
          <h2 id="report-summary" class="text-title-medium">{{ t('reports.detail.summary') }}</h2>
          <DefinitionList data-testid="summary" :items="summaryItems">
            <template #value="{ item }">
              <TextLink v-if="item.key === 'reported'" :to="`/players/${current.reportedHash}`">
                {{ item.value }}
              </TextLink>
              <template v-else-if="item.key === 'reporter'">
                <span aria-describedby="report-reporter-note">{{ item.value }}</span>
                <span id="report-reporter-note" class="ml-2 text-body-small text-on-surface-variant">{{ t('reports.detail.pseudonymous') }}</span>
              </template>
              <template v-else>{{ item.value }}</template>
            </template>
          </DefinitionList>
          <div v-if="canResolve" class="flex flex-wrap gap-2" role="group" :aria-label="t('reports.detail.actions')">
            <MdButton data-testid="apply-template" @click="applying = true">{{ t('reports.detail.applyTemplate') }}</MdButton>
            <MdButton variant="outlined" data-testid="dismiss" @click="dismissing = true">{{ t('reports.detail.dismiss') }}</MdButton>
          </div>
        </section>

        <section aria-labelledby="report-evidence" class="flex flex-col gap-3">
          <h2 id="report-evidence" class="text-title-medium">{{ t('reports.detail.evidence') }}</h2>
          <p v-if="failed.includes('evidence')" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container" data-testid="evidence-missing">
            {{ t('reports.detail.evidenceMissing', { n: missingEvidence }, missingEvidence) }}
          </p>
          <EvidenceList :items="evidence" :page="0" :total-pages="1" @delete="deleting = $event" />
        </section>

        <section aria-labelledby="report-others" class="flex flex-col gap-3">
          <h2 id="report-others" class="text-title-medium">{{ t('reports.detail.others') }}</h2>
          <p v-if="failed.includes('others')" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">{{ t('reports.detail.loadFailed') }}</p>
          <p v-else-if="others.length === 0" class="text-body-large text-on-surface-variant" data-testid="others-empty">{{ t('reports.detail.othersEmpty') }}</p>
          <ul v-else class="flex flex-col gap-2" data-testid="others">
            <li v-for="entry in others" :key="entry.identifier ?? entry.createdAt" class="flex flex-wrap items-center gap-3">
              <TextLink :to="`/reports/${entry.identifier}`">
                {{ t(`reports.category.${entry.category}`) }} · {{ formatDateTime(entry.createdAt, locale) }}
              </TextLink>
              <StatusPill :label="t(`reports.status.${entry.status ?? 'OPEN'}`)" :tone="OPEN.includes(entry.status ?? 'OPEN') ? 'tertiary' : 'neutral'" />
            </li>
          </ul>
        </section>

        <section aria-labelledby="report-active" class="flex flex-col gap-3">
          <h2 id="report-active" class="text-title-medium">{{ t('reports.detail.active') }}</h2>
          <p v-if="failed.includes('active')" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">{{ t('reports.detail.loadFailed') }}</p>
          <ActivePunishments v-else :active="active" />
        </section>
      </div>
    </ResourceState>
  </PageShell>

  <ApplyTemplateDialog v-if="current" :open="applying" :owner="current.reportedHash" :report="current" @close="applying = false" @done="done" />
  <ReportDismissDialog :open="dismissing" :report="current" @close="dismissing = false" @done="done" />
  <DeleteEvidenceDialog :open="deleting !== null" :item="deleting" @close="deleting = null" @done="done" />
  <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
</template>
