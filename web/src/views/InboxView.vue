<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { useApi } from '../api/inject'
import type { ApiError, ApiResult, CaseListItem, InboxItem } from '../api/types'
import { CASE_STATUS_TONE, PRIORITY_TONE } from '../components/case/caseTones'
import GuardTile from '../components/guard/GuardTile.vue'
import InboxRow from '../components/inbox/InboxRow.vue'
import InboxSection from '../components/inbox/InboxSection.vue'
import MdCard from '../components/md/MdCard.vue'
import MdDataTable from '../components/md/MdDataTable.vue'
import MdDialog from '../components/md/MdDialog.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import PageShell from '../components/PageShell.vue'
import ResourceState from '../components/ResourceState.vue'
import StatusPill from '../components/StatusPill.vue'
import { useResource } from '../composables/useResource'
import { assignedCases, unassignedUrgentCases, type CaseQueue } from '../inbox/caseQueues'
import { formatAge, formatDateTime, shortHash } from '../format'
import { APPEALS_ICON, CASES_ICON, REPORTS_ICON } from '../icons'
import { useSessionStore } from '../stores/session'

// The front page of the panel: the work queue (what is mine, what is urgent and unowned, what waits for a decision,
// an answer or a look), then the automatic escalations and the active punishments, which the dashboard used to show.
// Each section reads its own data, so one failed read blanks only its own section. The queue reads nothing that
// changes state; a change happens only when a moderator presses an action.
const ROWS = 10
const UNDO_WINDOW_MS = 10_000
const EMPTY_QUEUE: CaseQueue = { count: 0, truncated: false, items: [] }

const { t, locale } = useI18n()
const api = useApi()
const session = useSessionStore()
// The time the rows' ages are measured from: set when a read starts, so the ages match the data on screen.
const now = ref(Date.now())

const hasIdentity = computed(() => Boolean(session.me?.oid))
const assigned = useResource(async (): Promise<ApiResult<CaseQueue>> => {
  now.value = Date.now()
  const oid = session.me?.oid
  return oid ? assignedCases(api, oid) : { ok: true, data: EMPTY_QUEUE }
})
const urgent = useResource(() => {
  now.value = Date.now()
  return unassignedUrgentCases(api)
})
const inbox = useResource(() => {
  now.value = Date.now()
  return api.inbox()
})
const summary = useResource(() => api.dashboard())

async function reloadCases(): Promise<void> {
  await Promise.all([assigned.reload(), urgent.reload()])
}

// Actions. Each one sends the version the row was read at, so a change made meanwhile is refused, not overwritten.
const notice = ref<string | null>(null)
const undoable = ref<{ reference: string; label: string; version: number } | null>(null)
const closing = ref<CaseListItem | null>(null)

function failureText(error: ApiError): string {
  return error.kind === 'CONFLICT' ? t('inbox.conflict') : error.message
}

async function assignToMe(row: CaseListItem): Promise<void> {
  const oid = session.me?.oid
  if (!oid) return
  notice.value = null
  const result = await api.updateCase(row.punishmentIdentifier, { expectedVersion: row.version, assigneeOid: oid })
  if (!result.ok) {
    notice.value = failureText(result.error)
    return
  }
  undoable.value = { reference: row.punishmentIdentifier, label: row.shortId, version: result.data.version }
  await reloadCases()
}

async function undoAssign(): Promise<void> {
  const current = undoable.value
  undoable.value = null
  if (!current) return
  notice.value = null
  // The undo carries the version the assignment produced, so it is refused when someone else changed the case since.
  const result = await api.updateCase(current.reference, { expectedVersion: current.version, assigneeOid: null })
  notice.value = result.ok ? t('inbox.undone') : failureText(result.error)
  await reloadCases()
}

async function confirmClose(): Promise<void> {
  const row = closing.value
  closing.value = null
  if (!row) return
  notice.value = null
  const result = await api.updateCase(row.punishmentIdentifier, { expectedVersion: row.version, status: 'CLOSED' })
  if (!result.ok) {
    notice.value = failureText(result.error)
    return
  }
  await reloadCases()
}

// Rows: the case rows carry their own state, the inbox rows get the state of their section.
function caseRow(row: CaseListItem, extra: 'priority' | 'none') {
  return {
    href: `/cases/${row.punishmentIdentifier}`,
    typeLabel: t('inbox.types.case'),
    icon: CASES_ICON,
    id: row.shortId,
    player: row.playerName ?? row.ownerShort,
    pills: [
      { label: t(`case.status.${row.status}`), tone: CASE_STATUS_TONE[row.status] },
      ...(extra === 'priority' ? [{ label: t(`case.priority.${row.priority}`), tone: PRIORITY_TONE[row.priority] }] : []),
    ],
    age: formatAge(row.lastActivityAt, now.value, locale.value),
  }
}

/** An appeal or an answer leads to its own page, where the conversation and the decision are. */
function appealHref(item: InboxItem): string {
  return `/appeals/${item.id}`
}

function inboxRow(item: InboxItem, typeLabel: string, icon: string, href: string, state: { label: string; tone: 'primary' | 'secondary' | 'tertiary' }) {
  return {
    href,
    typeLabel,
    icon,
    id: item.reference ?? shortHash(item.id),
    player: item.owner ? shortHash(item.owner) : null,
    pills: [state],
    age: formatAge(item.since, now.value, locale.value),
  }
}

const appealRows = computed(() =>
  (inbox.data.value?.openAppeals.items ?? []).slice(0, ROWS).map((item) =>
    inboxRow(item, t('inbox.types.appeal'), APPEALS_ICON, appealHref(item), { label: t('inbox.states.appeal'), tone: 'secondary' }),
  ),
)
const answerRows = computed(() =>
  (inbox.data.value?.needsAnswer.items ?? []).slice(0, ROWS).map((item) =>
    inboxRow(item, t('inbox.types.appeal'), APPEALS_ICON, appealHref(item), { label: t('inbox.states.answer'), tone: 'tertiary' }),
  ),
)
const reportRows = computed(() =>
  (inbox.data.value?.newReports.items ?? []).slice(0, ROWS).map((item) =>
    inboxRow(item, t('inbox.types.report'), REPORTS_ICON, `/reports/${item.id}`, { label: t('inbox.states.report'), tone: 'primary' }),
  ),
)

const peak = computed(() => Math.max(1, ...(summary.data.value?.activePunishmentsBySection ?? []).map((s) => s.active)))

const columns = computed(() => [
  { key: 'section' as const, label: t('dashboard.escalations.section') },
  { key: 'owner' as const, label: t('dashboard.escalations.player') },
  { key: 'createdAt' as const, label: t('dashboard.escalations.when') },
  { key: 'lifted' as const, label: t('dashboard.escalations.status') },
])
</script>

<template>
  <PageShell :title="t('inbox.title')">
    <ul class="mb-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <li>
        <GuardTile />
      </li>
    </ul>

    <p v-if="!session.moderator" class="mb-6 text-body-large text-on-surface-variant">{{ t('inbox.readOnly') }}</p>
    <p
      v-if="notice"
      role="status"
      data-testid="notice"
      class="mb-6 rounded-md-md bg-secondary-container p-4 text-body-medium text-on-secondary-container"
    >
      {{ notice }}
    </p>

    <div class="flex flex-col gap-6">
      <InboxSection
        queue="assigned"
        :title="t('inbox.sections.assigned')"
        :count="hasIdentity ? (assigned.data.value?.count ?? null) : null"
        :truncated="assigned.data.value?.truncated"
        :show-all-to="hasIdentity ? '/cases?mine=1' : null"
        :loading="assigned.loading.value"
        :error="assigned.error.value"
        :empty-text="t('inbox.empty.assigned')"
        :note="hasIdentity ? null : t('inbox.noIdentity')"
        @retry="assigned.reload"
      >
        <InboxRow
          v-for="row in (assigned.data.value?.items ?? []).slice(0, ROWS)"
          :key="row.punishmentIdentifier"
          v-bind="caseRow(row, 'none')"
        >
          <button
            v-if="session.moderator"
            type="button"
            class="min-h-12 min-w-12 rounded-md-full px-4 text-label-large text-primary focus-ring md-state"
            @click="closing = row"
          >
            {{ t('inbox.close') }}
          </button>
        </InboxRow>
      </InboxSection>

      <InboxSection
        queue="urgent"
        :title="t('inbox.sections.urgent')"
        :count="urgent.data.value?.count ?? null"
        :truncated="urgent.data.value?.truncated"
        :show-all-to="'/cases?priority=URGENT'"
        :loading="urgent.loading.value"
        :error="urgent.error.value"
        :empty-text="t('inbox.empty.urgent')"
        @retry="urgent.reload"
      >
        <InboxRow
          v-for="row in (urgent.data.value?.items ?? []).slice(0, ROWS)"
          :key="row.punishmentIdentifier"
          v-bind="caseRow(row, 'priority')"
        >
          <button
            v-if="session.moderator"
            type="button"
            class="min-h-12 min-w-12 rounded-md-full px-4 text-label-large text-primary focus-ring md-state"
            @click="assignToMe(row)"
          >
            {{ t('inbox.assign') }}
          </button>
        </InboxRow>
      </InboxSection>

      <InboxSection
        queue="appeals"
        :title="t('inbox.sections.appeals')"
        :count="inbox.data.value?.openAppeals.count ?? null"
        :show-all-to="'/appeals'"
        :loading="inbox.loading.value"
        :error="inbox.error.value"
        :empty-text="t('inbox.empty.appeals')"
        @retry="inbox.reload"
      >
        <InboxRow v-for="row in appealRows" :key="row.href + row.id" v-bind="row" />
      </InboxSection>

      <InboxSection
        queue="answers"
        :title="t('inbox.sections.answers')"
        :count="inbox.data.value?.needsAnswer.count ?? null"
        :show-all-to="'/appeals'"
        :loading="inbox.loading.value"
        :error="inbox.error.value"
        :empty-text="t('inbox.empty.answers')"
        @retry="inbox.reload"
      >
        <InboxRow v-for="row in answerRows" :key="row.href + row.id" v-bind="row" />
      </InboxSection>

      <InboxSection
        queue="reports"
        :title="t('inbox.sections.reports')"
        :count="inbox.data.value?.newReports.count ?? null"
        :show-all-to="'/reports'"
        :loading="inbox.loading.value"
        :error="inbox.error.value"
        :empty-text="t('inbox.empty.reports')"
        @retry="inbox.reload"
      >
        <InboxRow v-for="row in reportRows" :key="row.href" v-bind="row" />
      </InboxSection>
    </div>

    <ResourceState
      :loading="summary.loading.value"
      :error="summary.error.value"
      :has-data="summary.data.value !== null"
      @retry="summary.reload"
    >
      <div v-if="summary.data.value" class="mt-6 flex flex-col gap-6">
        <section aria-labelledby="active-heading">
          <h2 id="active-heading" class="mb-3 text-title-medium">{{ t('dashboard.active') }}</h2>
          <ul class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            <li v-for="entry in summary.data.value.activePunishmentsBySection" :key="entry.section">
              <MdCard variant="outlined" :data-testid="`section-${entry.section}`">
                <div class="flex items-baseline justify-between gap-2">
                  <span class="text-title-medium">{{ entry.section }}</span>
                  <span class="text-headline-small">{{ entry.active }}</span>
                </div>
                <div class="mt-3 h-1 rounded-md-full bg-surface-container-highest" aria-hidden="true">
                  <div class="h-full rounded-md-full bg-primary" :style="{ width: `${(entry.active / peak) * 100}%` }" />
                </div>
              </MdCard>
            </li>
          </ul>
        </section>

        <section aria-labelledby="escalations-heading">
          <h2 id="escalations-heading" class="mb-3 text-title-medium">{{ t('dashboard.latest') }}</h2>
          <p v-if="summary.data.value.latestEscalations.length === 0" class="text-body-large text-on-surface-variant">
            {{ t('dashboard.empty') }}
          </p>
          <MdDataTable
            v-else
            :label="t('dashboard.latest')"
            :columns="columns"
            :rows="summary.data.value.latestEscalations"
          >
            <template #cell="{ row, column }">
              <RouterLink
                v-if="column.key === 'owner'"
                :to="`/players/${row.owner}`"
                :title="row.owner"
                class="text-primary underline underline-offset-2 focus-ring"
              >
                {{ shortHash(row.owner) }}
              </RouterLink>
              <template v-else-if="column.key === 'createdAt'">
                {{ row.createdAt === undefined ? '' : formatDateTime(row.createdAt, locale) }}
              </template>
              <StatusPill v-else-if="column.key === 'lifted' && row.lifted" :label="t('dashboard.escalations.lifted')" />
              <template v-else-if="column.key === 'lifted'">{{ t('dashboard.escalations.standing') }}</template>
              <template v-else>{{ row[column.key] }}</template>
            </template>
          </MdDataTable>
        </section>
      </div>
    </ResourceState>

    <MdDialog
      :open="closing !== null"
      :headline="t('inbox.closeDialog.headline', { reference: closing?.shortId ?? '' })"
      @close="closing = null"
    >
      <p>{{ t('inbox.closeDialog.text') }}</p>
      <template #actions>
        <button
          type="button"
          class="min-h-12 min-w-12 rounded-md-full px-4 text-label-large text-primary focus-ring md-state"
          @click="closing = null"
        >
          {{ t('inbox.closeDialog.cancel') }}
        </button>
        <button
          type="button"
          class="min-h-12 rounded-md-full bg-error px-4 text-label-large text-on-error focus-ring md-state"
          @click="confirmClose"
        >
          {{ t('inbox.closeDialog.confirm') }}
        </button>
      </template>
    </MdDialog>

    <MdSnackbar
      :open="undoable !== null"
      :message="undoable ? t('inbox.assignedNotice', { reference: undoable.label }) : ''"
      :action-label="t('inbox.undo')"
      :auto-close-ms="UNDO_WINDOW_MS"
      @action="undoAssign"
      @close="undoable = null"
    />
  </PageShell>
</template>
