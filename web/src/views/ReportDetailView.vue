<script setup lang="ts">
// One report with everything needed to judge it: who reported whom and why, the evidence (including the
// chat excerpt), the other reports against the same player and the player's active punishments. A
// moderator resolves an open report here: "Apply template" actions it with a punishment, "Dismiss" closes
// it with a note. Both use the resolve route. The reporter is shown by hash only, never by name or UUID.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { useApi } from '../api/inject'
import type { Evidence, Punishment, Report, ReportStatus } from '../api/types'
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
  <div class="flex flex-col gap-6">
    <RouterLink to="/reports" class="w-fit text-primary underline underline-offset-2 outline-none focus-visible:outline-2 focus-visible:outline-primary">
      {{ t('reports.detail.back') }}
    </RouterLink>

    <ResourceState :loading="report.loading.value" :error="report.error.value" :has-data="current !== null" @retry="report.reload">
      <div v-if="current" class="flex flex-col gap-8">
        <header class="flex flex-wrap items-center gap-3">
          <h2 class="text-headline-small">{{ t('reports.detail.title') }}</h2>
          <StatusPill :label="t(`reports.status.${current.status ?? 'OPEN'}`)" :tone="isOpen ? 'tertiary' : 'neutral'" data-testid="status" />
        </header>

        <section aria-labelledby="report-summary" class="flex flex-col gap-3">
          <h3 id="report-summary" class="text-title-medium">{{ t('reports.detail.summary') }}</h3>
          <dl class="grid grid-cols-[auto_1fr] gap-x-6 gap-y-2 text-body-large" data-testid="summary">
            <dt class="text-on-surface-variant">{{ t('reports.detail.reportedPlayer') }}</dt>
            <dd>
              <RouterLink
                :to="`/players/${current.reportedHash}`"
                :title="current.reportedHash"
                class="text-primary underline underline-offset-2 outline-none focus-visible:outline-2 focus-visible:outline-primary"
              >
                {{ playerName ?? shortHash(current.reportedHash) }}
              </RouterLink>
            </dd>
            <dt class="text-on-surface-variant">{{ t('reports.detail.reporter') }}</dt>
            <dd :title="current.reporterHash">
              {{ shortHash(current.reporterHash) }}
              <span class="text-body-small text-on-surface-variant">{{ t('reports.detail.pseudonymous') }}</span>
            </dd>
            <dt class="text-on-surface-variant">{{ t('reports.columns.category') }}</dt>
            <dd>{{ t(`reports.category.${current.category}`) }}</dd>
            <dt class="text-on-surface-variant">{{ t('reports.detail.reason') }}</dt>
            <dd class="break-words" data-testid="reason">{{ current.description || t('reports.detail.noReason') }}</dd>
            <dt class="text-on-surface-variant">{{ t('reports.columns.when') }}</dt>
            <dd>{{ formatDateTime(current.createdAt, locale) }}</dd>
            <template v-if="!isOpen">
              <dt class="text-on-surface-variant">{{ t('reports.detail.resolutionNote') }}</dt>
              <dd class="break-words" data-testid="resolution-note">{{ current.resolutionNote || t('reports.detail.noNote') }}</dd>
            </template>
          </dl>
          <div v-if="canResolve" class="flex flex-wrap gap-2" role="group" :aria-label="t('reports.detail.actions')">
            <MdButton data-testid="apply-template" @click="applying = true">{{ t('reports.detail.applyTemplate') }}</MdButton>
            <MdButton variant="outlined" data-testid="dismiss" @click="dismissing = true">{{ t('reports.detail.dismiss') }}</MdButton>
          </div>
        </section>

        <section aria-labelledby="report-evidence" class="flex flex-col gap-3">
          <h3 id="report-evidence" class="text-title-medium">{{ t('reports.detail.evidence') }}</h3>
          <p v-if="failed.includes('evidence')" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container" data-testid="evidence-missing">
            {{ t('reports.detail.evidenceMissing', { n: missingEvidence }, missingEvidence) }}
          </p>
          <EvidenceList :items="evidence" :page="0" :total-pages="1" @delete="deleting = $event" />
        </section>

        <section aria-labelledby="report-others" class="flex flex-col gap-3">
          <h3 id="report-others" class="text-title-medium">{{ t('reports.detail.others') }}</h3>
          <p v-if="failed.includes('others')" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">{{ t('reports.detail.loadFailed') }}</p>
          <p v-else-if="others.length === 0" class="text-body-large text-on-surface-variant" data-testid="others-empty">{{ t('reports.detail.othersEmpty') }}</p>
          <ul v-else class="flex flex-col gap-2" data-testid="others">
            <li v-for="entry in others" :key="entry.identifier ?? entry.createdAt" class="flex flex-wrap items-center gap-3">
              <RouterLink
                :to="`/reports/${entry.identifier}`"
                class="text-primary underline underline-offset-2 outline-none focus-visible:outline-2 focus-visible:outline-primary"
              >
                {{ t(`reports.category.${entry.category}`) }} · {{ formatDateTime(entry.createdAt, locale) }}
              </RouterLink>
              <StatusPill :label="t(`reports.status.${entry.status ?? 'OPEN'}`)" :tone="OPEN.includes(entry.status ?? 'OPEN') ? 'tertiary' : 'neutral'" />
            </li>
          </ul>
        </section>

        <section aria-labelledby="report-active" class="flex flex-col gap-3">
          <h3 id="report-active" class="text-title-medium">{{ t('reports.detail.active') }}</h3>
          <p v-if="failed.includes('active')" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">{{ t('reports.detail.loadFailed') }}</p>
          <ActivePunishments v-else :active="active" />
        </section>
      </div>
    </ResourceState>
  </div>

  <ApplyTemplateDialog v-if="current" :open="applying" :owner="current.reportedHash" :report="current" @close="applying = false" @done="done" />
  <ReportDismissDialog :open="dismissing" :report="current" @close="dismissing = false" @done="done" />
  <DeleteEvidenceDialog :open="deleting !== null" :item="deleting" @close="deleting = null" @done="done" />
  <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
</template>
