<script setup lang="ts">
// The preview of a rollback, a routed dialog over `/admin/rollback` (`/admin/rollback/new`). Nothing is revoked here: the
// preview freezes the set into a job, and the job's own routes start or cancel it. Opening the dialog sends only reads.
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useApi } from '../../api/inject'
import type { ApiError, RollbackPreview, RollbackPreviewRequest, StaffActor } from '../../api/types'
import { formatDateTime } from '../../format'
import type { SelectOption } from '../md/MdSelect.vue'
import DefinitionList from '../DefinitionList.vue'
import RoutedDialog from '../RoutedDialog.vue'
import MdButton from '../md/MdButton.vue'
import MdChip from '../md/MdChip.vue'
import MdProgress from '../md/MdProgress.vue'
import MdSelect from '../md/MdSelect.vue'
import MdTextField from '../md/MdTextField.vue'
import { isMinecraftUuid, rollbackErrorKey, windowMillis } from './rollback'

const MODES = ['STAFF', 'UNLINKED'] as const

const { t, locale } = useI18n()
const api = useApi()
const router = useRouter()

const actors = ref<StaffActor[]>([])
const mode = ref<(typeof MODES)[number]>('STAFF')
const staffOid = ref('')
const minecraftUuid = ref('')
const from = ref('')
const to = ref('')
const submitted = ref(false)
const busy = ref(false)
const error = ref('')
const preview = ref<RollbackPreview | null>(null)

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
const nothingToDo = computed(() => preview.value !== null && preview.value.revocable === 0 && preview.value.kicks === 0)

const countItems = computed(() => {
  const p = preview.value
  if (!p) return []
  return [
    { key: 'revocable', term: t('rollback.revocable'), value: String(p.revocable) },
    { key: 'already', term: t('rollback.alreadyInactive'), value: String(p.alreadyInactive) },
    { key: 'players', term: t('rollback.players'), value: String(p.players) },
    { key: 'kicks', term: t('rollback.kicks'), value: String(p.kicks) },
  ]
})
const kindItems = computed(() => Object.entries(preview.value?.byKind ?? {}).map(([kind, count]) => ({ key: kind, term: kind, value: String(count) })))
const sectionItems = computed(() => Object.entries(preview.value?.bySection ?? {}).map(([section, count]) => ({ key: section, term: section, value: String(count) })))

function errorText(failure: ApiError): string {
  const key = rollbackErrorKey(failure)
  return key ? t(key) : failure.message
}

function selector(): Pick<RollbackPreviewRequest, 'staffOid' | 'minecraftUuid'> {
  return mode.value === 'STAFF' ? { staffOid: staffOid.value } : { minecraftUuid: minecraftUuid.value.trim() }
}

onMounted(async () => {
  const result = await api.staffActors()
  if (result.ok) actors.value = result.data
  else error.value = errorText(result.error)
})

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
}

/** The job of the preview: its own address holds the start and the cancel. Nothing is started by opening it. */
function openJob(): void {
  if (!preview.value) return
  void router.push({ name: 'admin-rollback-job', params: { id: preview.value.jobId } })
}

function backToForm(): void {
  preview.value = null
  error.value = ''
}
</script>

<template>
  <RoutedDialog parent="admin-rollback" child="admin-rollback-new" :headline="t('rollback.new.title')">
    <form v-if="!preview" class="flex flex-col gap-4" novalidate data-testid="rollback-new-form" @submit.prevent="previewRollback">
      <p class="text-body-medium text-on-surface-variant">{{ t('rollback.intro') }}</p>
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

    <div v-else class="flex flex-col gap-4" data-testid="rollback-preview-step">
      <p role="status" class="text-body-large">{{ t('rollback.jobs.previewReady') }}</p>
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

      <div class="flex flex-wrap gap-2">
        <MdButton variant="text" data-testid="rollback-back" @click="backToForm">{{ t('rollback.back') }}</MdButton>
        <MdButton data-testid="rollback-open-job" @click="openJob">{{ t('rollback.jobs.open') }}</MdButton>
      </div>
    </div>
  </RoutedDialog>
</template>
