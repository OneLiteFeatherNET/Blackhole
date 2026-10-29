<script setup lang="ts">
// Rolls back what one staff member's punishments did in a time window: preview (counts only, nothing changes), then a
// confirmation that names the numbers and the two options, then the job's progress until it ends. The start carries an
// Idempotency-Key that is kept until the start succeeds, so a retry after a network failure answers the same job.
// ADMIN only: the panel renders nothing for any other role, and the backend enforces the role anyway.
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { ApiError, RollbackPreview, RollbackPreviewRequest, RollbackProgress, StaffActor } from '../../api/types'
import { formatDateTime } from '../../format'
import { useSessionStore } from '../../stores/session'
import { newIdempotencyKey } from '../kick/kickTargets'
import DefinitionList from '../DefinitionList.vue'
import MdButton from '../md/MdButton.vue'
import MdCheckbox from '../md/MdCheckbox.vue'
import MdChip from '../md/MdChip.vue'
import MdDialog from '../md/MdDialog.vue'
import MdProgress from '../md/MdProgress.vue'
import MdSelect, { type SelectOption } from '../md/MdSelect.vue'
import MdTextField from '../md/MdTextField.vue'
import { isFinished, isMinecraftUuid, pollRollback, realRollbackTimer, rollbackErrorKey, windowMillis, type RollbackTimer } from './rollback'

const props = withDefaults(defineProps<{ timer?: RollbackTimer }>(), { timer: () => realRollbackTimer })

const MODES = ['STAFF', 'UNLINKED'] as const
const ITEM_STATES = ['PENDING', 'REVOKED', 'SKIPPED', 'FAILED'] as const
const REASON_MAX = 500

const { t, locale } = useI18n()
const api = useApi()
const session = useSessionStore()

const actors = ref<StaffActor[]>([])
const mode = ref<(typeof MODES)[number]>('STAFF')
const staffOid = ref('')
const minecraftUuid = ref('')
const from = ref('')
const to = ref('')
const submitted = ref(false)
const busy = ref(false)
const error = ref('')
const step = ref<'form' | 'preview' | 'progress'>('form')
const preview = ref<RollbackPreview | null>(null)
const progress = ref<RollbackProgress | null>(null)
const polling = ref(false)
const confirming = ref(false)
const reason = ref('')
const suspendLink = ref(false)
const includeMarks = ref(false)

// The key of the start attempt in flight or to be retried; cleared once the start succeeds or a new preview is made.
let key: string | null = null
let stopPolling: () => void = () => {}

const staffOptions = computed<SelectOption[]>(() =>
  actors.value
    .filter((actor) => actor.kind === 'HUMAN')
    .map((actor) => ({ value: actor.oid, label: actor.displayName || t('staffActors.unnamed'), description: actor.oid })),
)
const range = computed(() => windowMillis(from.value, to.value))
const selectorReady = computed(() => (mode.value === 'STAFF' ? staffOid.value !== '' : isMinecraftUuid(minecraftUuid.value)))
const canPreview = computed(() => selectorReady.value && range.value !== null && !busy.value)
const uuidError = computed(() => (submitted.value && mode.value === 'UNLINKED' && !isMinecraftUuid(minecraftUuid.value) ? t('rollback.uuid.invalid') : ''))
const windowError = computed(() => (submitted.value && from.value !== '' && to.value !== '' && range.value === null ? t('rollback.window.invalid') : ''))
const selectedName = computed(() => {
  if (mode.value === 'UNLINKED') return minecraftUuid.value.trim()
  const actor = actors.value.find((candidate) => candidate.oid === staffOid.value)
  return actor?.displayName || t('staffActors.unnamed')
})
const reasonTrimmed = computed(() => reason.value.trim())
const reasonError = computed(() => (reason.value.length > REASON_MAX ? t('rollback.reason.tooLong') : ''))
const canExecute = computed(() => reasonTrimmed.value !== '' && reasonError.value === '' && !busy.value)
const nothingToDo = computed(() => preview.value !== null && preview.value.revocable === 0 && preview.value.kicks === 0)

const progressState = computed(() => progress.value?.state ?? 'RUNNING')
const finished = computed(() => isFinished(progressState.value))
const itemCount = (state: (typeof ITEM_STATES)[number]) => progress.value?.items?.[state] ?? 0
const total = computed(() => ITEM_STATES.reduce((sum, state) => sum + itemCount(state), 0))
const processed = computed(() => itemCount('REVOKED') + itemCount('SKIPPED') + itemCount('FAILED'))
const percent = computed(() => (total.value > 0 ? Math.round((processed.value * 100) / total.value) : undefined))

const countItems = computed(() => {
  const p = preview.value
  if (!p) return []
  return [
    { key: 'revocable', term: t('rollback.revocable'), value: String(p.revocable), testid: 'rollback-revocable' },
    { key: 'already', term: t('rollback.alreadyInactive'), value: String(p.alreadyInactive), testid: 'rollback-already' },
    { key: 'players', term: t('rollback.players'), value: String(p.players), testid: 'rollback-players' },
    { key: 'kicks', term: t('rollback.kicks'), value: String(p.kicks), testid: 'rollback-kicks' },
  ]
})
const kindItems = computed(() => Object.entries(preview.value?.byKind ?? {}).map(([kind, count]) => ({ key: kind, term: kind, value: String(count) })))
const sectionItems = computed(() => Object.entries(preview.value?.bySection ?? {}).map(([section, count]) => ({ key: section, term: section, value: String(count) })))
const stateItems = computed(() => ITEM_STATES.map((state) => ({ key: state, term: t(`rollback.items.${state}`), value: String(itemCount(state)) })))

function errorText(failure: ApiError): string {
  const key = rollbackErrorKey(failure)
  return key ? t(key) : failure.message
}

function selector(): Pick<RollbackPreviewRequest, 'staffOid' | 'minecraftUuid'> {
  return mode.value === 'STAFF' ? { staffOid: staffOid.value } : { minecraftUuid: minecraftUuid.value.trim() }
}

function startPolling(): void {
  stopPolling()
  const id = preview.value?.jobId
  if (!id) return
  polling.value = true
  error.value = ''
  stopPolling = pollRollback({
    fetch: () => api.rollbackProgress(id),
    timer: props.timer,
    onProgress(next) {
      progress.value = next
      if (isFinished(next.state)) polling.value = false
    },
    onError(failure) {
      polling.value = false
      error.value = errorText(failure)
    },
  })
}

async function loadActors(): Promise<void> {
  const result = await api.staffActors()
  if (result.ok) actors.value = result.data
  else error.value = errorText(result.error)
}

onMounted(() => {
  if (session.administrator) void loadActors()
})
onBeforeUnmount(() => stopPolling())

async function previewRollback(): Promise<void> {
  submitted.value = true
  const picked = range.value
  if (!canPreview.value || !picked) return
  busy.value = true
  error.value = ''
  const result = await api.previewRollback({ ...selector(), windowFrom: picked.from, windowTo: picked.to })
  busy.value = false
  if (!result.ok) {
    error.value = errorText(result.error)
    return
  }
  preview.value = result.data
  key = null
  step.value = 'preview'
}

function backToForm(): void {
  step.value = 'form'
  preview.value = null
  error.value = ''
}

function openConfirm(): void {
  error.value = ''
  confirming.value = true
}

async function execute(): Promise<void> {
  const current = preview.value
  if (!current || !canExecute.value) return
  busy.value = true
  error.value = ''
  key ??= newIdempotencyKey()
  const result = await api.executeRollback(current.jobId, { reason: reasonTrimmed.value, suspendLink: suspendLink.value, includeMarks: includeMarks.value }, key)
  busy.value = false
  if (!result.ok) {
    // The key stays: a retry with it answers the same job and writes nothing twice.
    error.value = errorText(result.error)
    return
  }
  key = null
  confirming.value = false
  progress.value = null
  step.value = 'progress'
  startPolling()
}

async function cancel(): Promise<void> {
  const id = preview.value?.jobId
  if (!id || busy.value) return
  busy.value = true
  error.value = ''
  const result = await api.cancelRollback(id)
  busy.value = false
  if (!result.ok) {
    // Already finished: read the job again so the screen shows how it ended.
    error.value = errorText(result.error)
    startPolling()
    return
  }
  stopPolling()
  polling.value = false
  const current = progress.value ?? { jobId: id, state: 'RUNNING', items: {} }
  progress.value = { ...current, state: result.data.state }
}

function startOver(): void {
  stopPolling()
  polling.value = false
  step.value = 'form'
  preview.value = null
  progress.value = null
  reason.value = ''
  suspendLink.value = false
  includeMarks.value = false
  error.value = ''
  key = null
}
</script>

<template>
  <section v-if="session.administrator" aria-labelledby="rollback-title" class="flex max-w-2xl flex-col gap-4" data-testid="staff-rollback">
    <h2 id="rollback-title" class="text-title-medium">{{ t('rollback.title') }}</h2>
    <p class="text-body-large text-on-surface-variant">{{ t('rollback.intro') }}</p>

    <form v-if="step === 'form'" class="flex flex-col gap-4" novalidate @submit.prevent="previewRollback">
      <div role="group" :aria-label="t('rollback.mode.label')" class="flex flex-wrap gap-2">
        <MdChip
          v-for="value in MODES"
          :key="value"
          :label="t(`rollback.mode.${value}`)"
          :selected="mode === value"
          :data-testid="`mode-${value}`"
          @click="mode = value"
        />
      </div>

      <MdSelect
        v-if="mode === 'STAFF'"
        v-model="staffOid"
        :label="t('rollback.staff.label')"
        :placeholder="t('rollback.staff.choose')"
        :options="staffOptions"
        data-testid="rollback-staff"
      />
      <MdTextField
        v-else
        v-model="minecraftUuid"
        :label="t('rollback.uuid.label')"
        :error="uuidError"
        :supporting-text="t('rollback.uuid.hint')"
        data-testid="rollback-uuid"
      />

      <MdTextField v-model="from" type="datetime-local" required :label="t('rollback.window.from')" data-testid="rollback-from" />
      <MdTextField v-model="to" type="datetime-local" required :label="t('rollback.window.to')" :error="windowError" data-testid="rollback-to" />

      <MdProgress v-if="busy" :label="t('rollback.loading')" />
      <p v-if="error" role="alert" data-testid="error" class="rounded-md-md bg-error-container p-3 text-on-error-container">{{ error }}</p>

      <div>
        <MdButton type="submit" :disabled="!canPreview" data-testid="rollback-preview">{{ t('rollback.preview') }}</MdButton>
      </div>
    </form>

    <div v-else-if="step === 'preview' && preview" class="flex flex-col gap-4">
      <DefinitionList :items="countItems" />
      <div v-if="kindItems.length" class="flex flex-col gap-2">
        <h3 class="text-title-small">{{ t('rollback.byKind') }}</h3>
        <DefinitionList :items="kindItems" />
      </div>
      <div v-if="sectionItems.length" class="flex flex-col gap-2">
        <h3 class="text-title-small">{{ t('rollback.bySection') }}</h3>
        <DefinitionList :items="sectionItems" />
      </div>
      <p data-testid="rollback-expiry" class="text-body-medium text-on-surface-variant">
        {{ t('rollback.expires', { time: formatDateTime(preview.previewExpiresAt, locale) }) }}
      </p>
      <p v-if="nothingToDo" role="status" data-testid="rollback-nothing" class="text-body-large">{{ t('rollback.nothing') }}</p>
      <p v-if="error" role="alert" data-testid="error" class="rounded-md-md bg-error-container p-3 text-on-error-container">{{ error }}</p>

      <div class="flex flex-wrap gap-2">
        <MdButton variant="text" data-testid="rollback-back" @click="backToForm">{{ t('rollback.back') }}</MdButton>
        <MdButton :disabled="nothingToDo" data-testid="rollback-continue" @click="openConfirm">{{ t('rollback.continue') }}</MdButton>
      </div>
    </div>

    <div v-else class="flex flex-col gap-4" data-testid="rollback-progress">
      <div>
        <MdChip variant="status" :label="t(`rollback.state.${progressState}`)" data-testid="rollback-state" />
      </div>
      <MdProgress :label="t('rollback.progress.label')" :value="percent" />
      <DefinitionList :items="stateItems" />
      <p v-if="finished" role="status" data-testid="rollback-done" class="text-body-large">{{ t(`rollback.done.${progressState}`) }}</p>
      <p v-if="error" role="alert" data-testid="error" class="rounded-md-md bg-error-container p-3 text-on-error-container">{{ error }}</p>

      <div class="flex flex-wrap gap-2">
        <MdButton v-if="!finished" variant="outlined" :disabled="busy" data-testid="rollback-cancel" @click="cancel">{{ t('rollback.progress.cancel') }}</MdButton>
        <MdButton v-if="!finished && !polling" variant="tonal" data-testid="rollback-retry" @click="startPolling">{{ t('rollback.progress.retry') }}</MdButton>
        <MdButton v-if="finished" variant="tonal" data-testid="rollback-again" @click="startOver">{{ t('rollback.progress.again') }}</MdButton>
      </div>
    </div>

    <p v-if="actors.length === 0 && step === 'form' && !busy" class="text-body-medium text-on-surface-variant">{{ t('rollback.noStaff') }}</p>
  </section>

  <MdDialog :open="confirming" :headline="t('rollback.confirm.title', { name: selectedName })" @close="confirming = false">
    <p data-testid="rollback-confirm-counts">
      {{ t('rollback.confirm.counts', { revocable: preview?.revocable ?? 0, players: preview?.players ?? 0, kicks: preview?.kicks ?? 0 }) }}
    </p>
    <p class="mt-2">{{ t('rollback.confirm.manual') }}</p>
    <div class="mt-4 flex flex-col gap-3">
      <MdCheckbox
        v-model="includeMarks"
        :label="t('rollback.options.marks', { count: preview?.kicks ?? 0 })"
        :supporting-text="t('rollback.options.marksHint')"
        data-testid="option-marks"
      />
      <MdCheckbox v-model="suspendLink" :label="t('rollback.options.suspend')" :supporting-text="t('rollback.options.suspendHint')" data-testid="option-suspend" />
    </div>
    <MdTextField
      v-model="reason"
      multiline
      :rows="2"
      required
      :label="t('rollback.reason.label')"
      :error="reasonError"
      :supporting-text="t('rollback.reason.hint')"
      data-testid="rollback-reason"
      class="mt-4"
    />
    <p v-if="error" role="alert" data-testid="error" class="mt-3 rounded-md-md bg-error-container p-3 text-on-error-container">{{ error }}</p>
    <template #actions>
      <MdButton variant="text" data-testid="rollback-cancel-dialog" @click="confirming = false">{{ t('actions.cancel') }}</MdButton>
      <MdButton variant="error" :disabled="!canExecute" data-testid="rollback-execute" @click="execute">{{ t('rollback.confirm.submit') }}</MdButton>
    </template>
  </MdDialog>
</template>
