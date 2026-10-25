<script setup lang="ts">
// The case's facts and, for a moderator, its staff metadata. Every change carries the version that
// was read; when someone else changed the case first (409) nothing is overwritten: the user is told
// and reloads.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { Case, CasePriority, CaseStatus, CaseUpdate } from '../../api/types'
import { formatDateTime } from '../../format'
import { useSessionStore } from '../../stores/session'
import DefinitionList from '../DefinitionList.vue'
import MdButton from '../md/MdButton.vue'
import MdChip from '../md/MdChip.vue'
import MdSelect from '../md/MdSelect.vue'
import MdTextField from '../md/MdTextField.vue'
import StatusPill from '../StatusPill.vue'

const NONE = '__none__'
const STATUSES: CaseStatus[] = ['OPEN', 'IN_REVIEW', 'WAITING', 'CLOSED']
const PRIORITIES: CasePriority[] = ['LOW', 'NORMAL', 'HIGH', 'URGENT']
const TAG = /^[a-z0-9-]{1,32}$/
const MAX_TAGS = 10

const props = defineProps<{ kase: Case }>()
const emit = defineEmits<{ updated: [kase: Case]; reload: []; message: [text: string] }>()

const { t, locale } = useI18n()
const api = useApi()
const session = useSessionStore()

const busy = ref(false)
const conflict = ref(false)
const error = ref('')
const newTag = ref('')
const tagError = ref('')

const editable = computed(() => session.moderator)

const statusOptions = computed(() => STATUSES.map((value) => ({ value, label: t(`case.status.${value}`) })))
const priorityOptions = computed(() => PRIORITIES.map((value) => ({ value, label: t(`case.priority.${value}`) })))

// The API has no staff directory for moderators, so the picker offers what is known here: nobody,
// the signed-in person and the current assignee.
const assigneeOptions = computed(() => {
  const options = [{ value: NONE, label: t('case.header.unassigned') }]
  const me = session.me
  if (me) options.push({ value: me.oid, label: t('case.header.assignMe', { name: me.displayName ?? `${me.oid.slice(0, 8)}…` }) })
  const current = props.kase.assigneeOid
  if (current && current !== me?.oid) options.push({ value: current, label: props.kase.assigneeName ?? `${current.slice(0, 8)}…` })
  return options
})

const stateTone = computed(() => (props.kase.punishmentState === 'ACTIVE' ? 'error' : 'neutral'))

const facts = computed(() => [
  { term: t('case.header.player'), value: `${props.kase.ownerPrefix}…`, testid: 'player-prefix' },
  ...(props.kase.section ? [{ term: t('case.header.section'), value: props.kase.section }] : []),
  { term: t('case.header.issued'), value: formatDateTime(props.kase.punishmentCreatedAt, locale.value) },
])

// A fresh version means the user is looking at current data again.
watch(
  () => props.kase.version,
  () => {
    conflict.value = false
    error.value = ''
  },
)

async function apply(change: Omit<CaseUpdate, 'expectedVersion'>): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = ''
  const result = await api.updateCase(props.kase.punishmentIdentifier, { expectedVersion: props.kase.version, ...change })
  busy.value = false
  if (result.ok) {
    conflict.value = false
    emit('updated', result.data)
    emit('message', t('case.header.saved'))
    return
  }
  if (result.error.status === 409) conflict.value = true
  else error.value = result.error.message
}

const setStatus = (value: string) => apply({ status: value as CaseStatus })
const setPriority = (value: string) => apply({ priority: value as CasePriority })
const setAssignee = (value: string) => apply({ assigneeOid: value === NONE ? null : value })

function addTag(): void {
  const tag = newTag.value.trim().toLowerCase()
  if (!tag) return
  if (!TAG.test(tag)) {
    tagError.value = t('case.header.tagInvalid')
    return
  }
  if (props.kase.tags.includes(tag)) {
    tagError.value = t('case.header.tagDuplicate')
    return
  }
  if (props.kase.tags.length >= MAX_TAGS) {
    tagError.value = t('case.header.tagLimit', { max: MAX_TAGS })
    return
  }
  tagError.value = ''
  newTag.value = ''
  void apply({ tags: [...props.kase.tags, tag] })
}

const removeTag = (tag: string) => apply({ tags: props.kase.tags.filter((existing) => existing !== tag) })
</script>

<template>
  <section :aria-label="t('case.header.title')" class="flex flex-col gap-4 rounded-md-lg bg-surface-container p-4" data-testid="case-header">
    <div class="flex flex-wrap items-center gap-2">
      <StatusPill :label="t(`case.state.${kase.punishmentState}`)" :tone="stateTone" data-testid="punishment-state" />
      <StatusPill v-if="kase.permanent" :label="t('player.permanent')" tone="error" />
    </div>

    <DefinitionList :items="facts" />

    <p v-if="!editable" class="text-body-medium text-on-surface-variant" data-testid="read-only">{{ t('case.header.readOnly') }}</p>

    <div class="grid gap-4 sm:grid-cols-3">
      <MdSelect :model-value="kase.status" :label="t('case.header.status')" :options="statusOptions" :disabled="!editable || busy" data-testid="status" @update:model-value="setStatus" />
      <MdSelect :model-value="kase.assigneeOid ?? NONE" :label="t('case.header.assignee')" :options="assigneeOptions" :disabled="!editable || busy" data-testid="assignee" @update:model-value="setAssignee" />
      <MdSelect :model-value="kase.priority" :label="t('case.header.priority')" :options="priorityOptions" :disabled="!editable || busy" data-testid="priority" @update:model-value="setPriority" />
    </div>

    <div>
      <p class="mb-2 text-label-medium text-on-surface-variant">{{ t('case.header.tags') }}</p>
      <p v-if="kase.tags.length === 0" class="text-body-medium text-on-surface-variant">{{ t('case.header.noTags') }}</p>
      <ul v-else class="flex flex-wrap gap-2" data-testid="tags" :aria-label="t('case.header.tags')">
        <li v-for="tag in kase.tags" :key="tag" class="inline-flex items-center gap-1">
          <MdChip variant="status" :label="tag" />
          <button
            v-if="editable"
            type="button"
            :disabled="busy"
            :aria-label="t('case.header.removeTag', { tag })"
            class="md-state inline-flex h-6 w-6 items-center justify-center rounded-md-full"
            :data-testid="`remove-tag-${tag}`"
            @click="removeTag(tag)"
          >
            <svg viewBox="0 0 24 24" class="h-4 w-4" aria-hidden="true"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" fill="currentColor" /></svg>
          </button>
        </li>
      </ul>
      <form v-if="editable" class="mt-3 flex max-w-md items-start gap-2" @submit.prevent="addTag">
        <div class="flex-1">
          <MdTextField v-model="newTag" :label="t('case.header.newTag')" :error="tagError" :supporting-text="t('case.header.tagHint')" :disabled="busy" data-testid="new-tag" />
        </div>
        <MdButton type="submit" variant="tonal" :disabled="busy" class="mt-2" data-testid="add-tag">{{ t('case.header.addTag') }}</MdButton>
      </form>
    </div>

    <div v-if="conflict" role="alert" class="flex flex-wrap items-center gap-3 rounded-md-md bg-error-container p-4 text-on-error-container" data-testid="conflict">
      <p class="min-w-0 flex-1 text-body-medium">{{ t('case.header.conflict') }}</p>
      <MdButton variant="outlined" data-testid="reload" @click="emit('reload')">{{ t('case.header.reload') }}</MdButton>
    </div>
    <p v-if="error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container" data-testid="header-error">{{ error }}</p>
  </section>
</template>
