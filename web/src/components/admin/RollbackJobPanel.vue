<script setup lang="ts">
// One rollback job by its address (`/admin/rollback/:id`): its state and progress. A preview is read once; a running job
// is read again until it ends. The start and the cancel are child routes (`/execute`, `/cancel`), so opening this page
// sends no state change. The page is read again whenever its route changes (after a start or a cancel).
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { useApi } from '../../api/inject'
import type { ApiError, RollbackProgress } from '../../api/types'
import CopyLink from '../CopyLink.vue'
import DefinitionList from '../DefinitionList.vue'
import MdButton from '../md/MdButton.vue'
import MdChip from '../md/MdChip.vue'
import MdProgress from '../md/MdProgress.vue'
import { isFinished, pollRollback, realRollbackTimer, rollbackErrorKey, type RollbackTimer } from './rollback'

const props = withDefaults(defineProps<{ id: string; timer?: RollbackTimer }>(), { timer: () => realRollbackTimer })

const ITEM_STATES = ['PENDING', 'REVOKED', 'SKIPPED', 'FAILED'] as const

const { t } = useI18n()
const api = useApi()
const route = useRoute()

const progress = ref<RollbackProgress | null>(null)
const missing = ref(false)
const error = ref('')
let stopPolling: () => void = () => {}

const progressState = computed(() => progress.value?.state ?? 'PREVIEW')
const finished = computed(() => isFinished(progressState.value))
const itemCount = (state: (typeof ITEM_STATES)[number]) => progress.value?.items?.[state] ?? 0
const total = computed(() => ITEM_STATES.reduce((sum, state) => sum + itemCount(state), 0))
const processed = computed(() => itemCount('REVOKED') + itemCount('SKIPPED') + itemCount('FAILED'))
const percent = computed(() => (total.value > 0 ? Math.round((processed.value * 100) / total.value) : undefined))
const stateItems = computed(() => ITEM_STATES.map((state) => ({ key: state, term: t(`rollback.items.${state}`), value: String(itemCount(state)) })))

function errorText(failure: ApiError): string {
  const key = rollbackErrorKey(failure)
  return key ? t(key) : failure.message
}

function start(): void {
  stopPolling()
  progress.value = null
  missing.value = false
  error.value = ''
  stopPolling = pollRollback({
    fetch: () => api.rollbackProgress(props.id),
    timer: props.timer,
    // Only a running job changes by itself; a preview waits for the start, so it is read once.
    stopWhen: (state) => state !== 'RUNNING',
    onProgress(next) {
      progress.value = next
    },
    onError(failure) {
      if (failure.kind === 'NOT_FOUND') missing.value = true
      else error.value = errorText(failure)
    },
  })
}

// Read again when the job or the route changes: a start (`/execute` closes to here) turns the preview into a running job.
watch(() => [props.id, route.name], start, { immediate: true })
onBeforeUnmount(() => stopPolling())
</script>

<template>
  <section aria-labelledby="rollback-job-title" class="flex flex-col gap-4" data-testid="rollback-job">
    <h3 id="rollback-job-title" class="text-title-medium">{{ t('rollback.jobs.title') }}</h3>

    <p v-if="missing" role="alert" data-testid="rollback-job-missing" class="text-body-large">{{ t('rollback.jobs.notFound') }}</p>
    <template v-else-if="progress">
      <div>
        <MdChip variant="status" :label="t(`rollback.state.${progressState}`)" data-testid="rollback-state" />
      </div>
      <MdProgress v-if="progressState === 'RUNNING'" :label="t('rollback.progress.label')" :value="percent" />
      <DefinitionList :items="stateItems" />
      <p v-if="progressState === 'PREVIEW'" role="status" data-testid="rollback-preview-state" class="text-body-large">{{ t('rollback.jobs.pending', { count: total }) }}</p>
      <p v-if="finished" role="status" data-testid="rollback-done" class="text-body-large">{{ t(`rollback.done.${progressState}`) }}</p>
      <p v-if="progressState === 'RUNNING'" role="status" data-testid="rollback-running" class="text-body-medium text-on-surface-variant">{{ t('rollback.started') }}</p>
    </template>
    <MdProgress v-else-if="!error" :label="t('rollback.loading')" />

    <p v-if="error" role="alert" data-testid="error" class="rounded-md-md bg-error-container p-3 text-on-error-container">{{ error }}</p>

    <div v-if="progress || error" class="flex flex-wrap gap-2">
      <MdButton v-if="progressState === 'PREVIEW'" :to="{ name: 'admin-rollback-execute', params: { id } }" as="link" data-testid="rollback-execute-link">
        {{ t('rollback.jobs.execute') }}
      </MdButton>
      <MdButton v-if="!finished" variant="outlined" :to="{ name: 'admin-rollback-cancel', params: { id } }" as="link" data-testid="rollback-cancel-link">
        {{ t('rollback.progress.cancel') }}
      </MdButton>
      <MdButton variant="tonal" :to="{ name: 'admin-rollback-new' }" as="link" data-testid="rollback-again">{{ t('rollback.jobs.newLink') }}</MdButton>
      <MdButton v-if="error" variant="tonal" data-testid="rollback-retry" @click="start">{{ t('rollback.progress.retry') }}</MdButton>
    </div>
    <CopyLink />
  </section>
</template>
