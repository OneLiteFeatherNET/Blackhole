<script setup lang="ts">
// The bulk panel of the case list: one shared change for the selected cases, sent through the bulk route. The form
// stays until Apply; then the chunks run with determinate progress, and each case gets its outcome. The page hears
// `done` once with the items, so it can reload and clear the selection; a conflicted case can be reloaded from here.
// The cases are snapshotted at Apply, so the result still names them after the page has cleared its selection.
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { CaseBulkChange, CaseBulkItem, CaseBulkOutcome, CasePriority, CaseStatus } from '../../api/types'
import type { SelectableCase } from '../../composables/useCaseSelection'
import { useSessionStore } from '../../stores/session'
import MdButton from '../md/MdButton.vue'
import MdDialog from '../md/MdDialog.vue'
import MdProgress from '../md/MdProgress.vue'
import MdSelect from '../md/MdSelect.vue'
import MdTextField from '../md/MdTextField.vue'
import StatusPill from '../StatusPill.vue'

const props = defineProps<{ open: boolean; cases: SelectableCase[] }>()
const emit = defineEmits<{ close: []; done: [items: CaseBulkItem[]]; reload: [] }>()

const KEEP = '__keep__'
const NONE = '__none__'
const STATUSES: CaseStatus[] = ['OPEN', 'IN_REVIEW', 'WAITING', 'CLOSED']
const PRIORITIES: CasePriority[] = ['LOW', 'NORMAL', 'HIGH', 'URGENT']
const TAG = /^[a-z0-9-]{1,32}$/
const OUTCOME_TONE: Record<CaseBulkOutcome, 'primary' | 'neutral' | 'tertiary' | 'error'> = {
  UPDATED: 'primary',
  UNCHANGED: 'neutral',
  CONFLICT: 'tertiary',
  NOT_FOUND: 'neutral',
  FAILED: 'error',
}

const { t } = useI18n()
const api = useApi()
const session = useSessionStore()

const phase = ref<'form' | 'running' | 'done'>('form')
const progress = ref(0)
const items = ref<CaseBulkItem[]>([])
const snapshot = ref<SelectableCase[]>([])
const form = reactive({ status: KEEP, assignee: KEEP, priority: KEEP })
const addTags = ref<string[]>([])
const removeTags = ref<string[]>([])
const newAdd = ref('')
const newRemove = ref('')
const addError = ref('')
const removeError = ref('')

const statusOptions = computed(() => [{ value: KEEP, label: t('case.bulk.keep') }, ...STATUSES.map((value) => ({ value, label: t(`case.status.${value}`) }))])
const priorityOptions = computed(() => [{ value: KEEP, label: t('case.bulk.keep') }, ...PRIORITIES.map((value) => ({ value, label: t(`case.priority.${value}`) }))])
// As in the case header: the API has no staff directory for moderators, so the choices are nobody and the signed-in person.
const assigneeOptions = computed(() => {
  const options = [
    { value: KEEP, label: t('case.bulk.keep') },
    { value: NONE, label: t('case.header.unassigned') },
  ]
  const me = session.me
  if (me) options.push({ value: me.oid, label: t('case.header.assignMe', { name: me.displayName ?? `${me.oid.slice(0, 8)}…` }) })
  return options
})

const hasChange = computed(
  () => form.status !== KEEP || form.assignee !== KEEP || form.priority !== KEEP || addTags.value.length > 0 || removeTags.value.length > 0,
)
const canApply = computed(() => phase.value === 'form' && hasChange.value && props.cases.length > 0)
// After Apply the page may have cleared its selection; the headline still names the cases that were sent.
const headlineCount = computed(() => (phase.value === 'form' ? props.cases.length : snapshot.value.length))

const counts = computed(() => {
  const count = (outcome: CaseBulkOutcome) => items.value.filter((item) => item.outcome === outcome).length
  return {
    updated: count('UPDATED'),
    unchanged: count('UNCHANGED'),
    conflicts: count('CONFLICT'),
    notFound: count('NOT_FOUND'),
    failed: count('FAILED'),
  }
})
const summary = computed(() => t('case.bulk.summary', counts.value))

// A new batch starts from an empty form once the last one is read; a batch still running is left alone.
watch(
  () => props.open,
  (open) => {
    if (!open && phase.value === 'done') reset()
  },
)

function reset(): void {
  phase.value = 'form'
  items.value = []
  form.status = KEEP
  form.assignee = KEEP
  form.priority = KEEP
  addTags.value = []
  removeTags.value = []
}

function shortIdOf(punishmentIdentifier: string): string {
  return snapshot.value.find((c) => c.punishmentIdentifier === punishmentIdentifier)?.shortId ?? punishmentIdentifier
}

function addTag(): void {
  const tag = newAdd.value.trim().toLowerCase()
  if (!tag) return
  if (!TAG.test(tag)) {
    addError.value = t('case.header.tagInvalid')
    return
  }
  if (addTags.value.includes(tag)) {
    addError.value = t('case.header.tagDuplicate')
    return
  }
  addError.value = ''
  newAdd.value = ''
  addTags.value = [...addTags.value, tag]
}

function addRemoveTag(): void {
  const tag = newRemove.value.trim().toLowerCase()
  if (!tag) return
  if (!TAG.test(tag)) {
    removeError.value = t('case.header.tagInvalid')
    return
  }
  if (removeTags.value.includes(tag)) {
    removeError.value = t('case.header.tagDuplicate')
    return
  }
  removeError.value = ''
  newRemove.value = ''
  removeTags.value = [...removeTags.value, tag]
}

async function apply(): Promise<void> {
  if (!canApply.value) return
  const sent = [...props.cases]
  snapshot.value = sent
  const change: CaseBulkChange = { cases: sent.map((c) => ({ punishmentIdentifier: c.punishmentIdentifier, expectedVersion: c.version })) }
  if (form.status !== KEEP) change.status = form.status as CaseStatus
  if (form.priority !== KEEP) change.priority = form.priority as CasePriority
  // An absent assigneeOid leaves the assignee alone; null removes it.
  if (form.assignee === NONE) change.assigneeOid = null
  else if (form.assignee !== KEEP) change.assigneeOid = form.assignee
  if (addTags.value.length > 0) change.addTags = [...addTags.value]
  if (removeTags.value.length > 0) change.removeTags = [...removeTags.value]

  phase.value = 'running'
  progress.value = 0
  const result = await api.bulkEditCases(change, {
    onProgress: (done, total) => {
      progress.value = total === 0 ? 100 : Math.round((done * 100) / total)
    },
  })
  items.value = result.ok ? result.data : []
  phase.value = 'done'
  emit('done', items.value)
}
</script>

<template>
  <MdDialog :open="props.open" :headline="t('case.bulk.title', { count: headlineCount })" wide @close="emit('close')">
    <div v-if="phase === 'form'" class="flex flex-col gap-4">
      <p class="text-body-medium text-on-surface-variant">{{ t('case.bulk.hint') }}</p>

      <div class="grid gap-4 sm:grid-cols-3">
        <MdSelect v-model="form.status" :label="t('case.header.status')" :options="statusOptions" data-testid="bulk-status" />
        <MdSelect v-model="form.assignee" :label="t('case.header.assignee')" :options="assigneeOptions" data-testid="bulk-assignee" />
        <MdSelect v-model="form.priority" :label="t('case.header.priority')" :options="priorityOptions" data-testid="bulk-priority" />
      </div>

      <div class="grid gap-4 sm:grid-cols-2">
        <div>
          <form class="flex items-start gap-2" data-testid="bulk-add-tag-form" @submit.prevent="addTag">
            <div class="flex-1">
              <MdTextField v-model="newAdd" :label="t('case.bulk.addTags')" :error="addError" data-testid="bulk-add-tag" />
            </div>
            <MdButton type="submit" variant="tonal" class="mt-2">{{ t('case.header.addTag') }}</MdButton>
          </form>
          <ul v-if="addTags.length > 0" class="mt-2 flex flex-wrap gap-2" data-testid="bulk-added-tags" :aria-label="t('case.bulk.addTags')">
            <li v-for="tag in addTags" :key="tag" class="inline-flex items-center gap-1">
              <StatusPill :label="tag" />
              <button type="button" class="md-state rounded-md-full px-2 text-label-medium" :aria-label="t('case.header.removeTag', { tag })" @click="addTags = addTags.filter((x) => x !== tag)">×</button>
            </li>
          </ul>
        </div>
        <div>
          <form class="flex items-start gap-2" data-testid="bulk-remove-tag-form" @submit.prevent="addRemoveTag">
            <div class="flex-1">
              <MdTextField v-model="newRemove" :label="t('case.bulk.removeTags')" :error="removeError" data-testid="bulk-remove-tag" />
            </div>
            <MdButton type="submit" variant="tonal" class="mt-2">{{ t('case.header.addTag') }}</MdButton>
          </form>
          <ul v-if="removeTags.length > 0" class="mt-2 flex flex-wrap gap-2" data-testid="bulk-removed-tags" :aria-label="t('case.bulk.removeTags')">
            <li v-for="tag in removeTags" :key="tag" class="inline-flex items-center gap-1">
              <StatusPill :label="tag" />
              <button type="button" class="md-state rounded-md-full px-2 text-label-medium" :aria-label="t('case.header.removeTag', { tag })" @click="removeTags = removeTags.filter((x) => x !== tag)">×</button>
            </li>
          </ul>
        </div>
      </div>

      <div class="flex justify-end gap-2">
        <MdButton variant="text" @click="emit('close')">{{ t('case.bulk.close') }}</MdButton>
        <MdButton variant="filled" :disabled="!canApply" data-testid="bulk-apply" @click="apply">
          {{ t('case.bulk.apply', { count: props.cases.length }) }}
        </MdButton>
      </div>
    </div>

    <div v-else-if="phase === 'running'" class="flex flex-col gap-3">
      <p class="text-body-medium">{{ t('case.bulk.running') }}</p>
      <MdProgress :label="t('case.bulk.running')" :value="progress" />
    </div>

    <div v-else class="flex flex-col gap-4">
      <p role="status" aria-live="polite" class="text-body-medium text-on-surface">{{ summary }}</p>
      <ul class="flex flex-col gap-2">
        <li v-for="item in items" :key="item.punishmentIdentifier" data-testid="bulk-result" class="flex flex-wrap items-center gap-2 text-body-medium">
          <span>{{ shortIdOf(item.punishmentIdentifier) }}</span>
          <StatusPill :label="t(`case.bulk.outcome.${item.outcome}`)" :tone="OUTCOME_TONE[item.outcome]" />
          <span v-if="item.message" class="text-on-surface-variant">{{ item.message }}</span>
        </li>
      </ul>
      <div class="flex flex-wrap justify-end gap-2">
        <MdButton v-if="counts.conflicts > 0" variant="outlined" data-testid="bulk-reload" @click="emit('reload')">{{ t('case.bulk.reload') }}</MdButton>
        <MdButton variant="text" @click="emit('close')">{{ t('case.bulk.close') }}</MdButton>
      </div>
    </div>
  </MdDialog>
</template>
