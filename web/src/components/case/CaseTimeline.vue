<script setup lang="ts">
// The vertical timeline of a case, newest first, with filters and "load more" by cursor (no
// infinite scroll, so keyboard focus is never taken from the user while events load).
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { ActorTrust, ApiError, CaseTimelineItem } from '../../api/types'
import { useSessionStore } from '../../stores/session'
import MdButton from '../md/MdButton.vue'
import MdSelect from '../md/MdSelect.vue'
import MdTextField from '../md/MdTextField.vue'
import ResourceState from '../ResourceState.vue'
import CaseEventEntry from './CaseEventEntry.vue'
import { isNoteVersion, isRedacted, noteIdOf, TYPE_GROUPS } from './caseEvents'
import RedactNoteDialog from './RedactNoteDialog.vue'

const PAGE_SIZE = 50
const ALL = 'all'

const props = defineProps<{ reference: string }>()
const emit = defineEmits<{ changed: [message: string] }>()

const { t } = useI18n()
const api = useApi()
const session = useSessionStore()

const items = ref<CaseTimelineItem[]>([])
const nextCursor = ref<string | null>(null)
const loaded = ref(false)
const loading = ref(false)
const loadingMore = ref(false)
const error = ref<ApiError | null>(null)

const group = ref(ALL)
const trust = ref(ALL)
const from = ref('')
const to = ref('')

const editing = ref<string | null>(null)
const redacting = ref<string | null>(null)
const list = ref<HTMLElement | null>(null)

const groupOptions = computed(() => [
  { value: ALL, label: t('case.timeline.allTypes') },
  ...TYPE_GROUPS.map((value) => ({ value, label: t(`case.timeline.group.${value}`) })),
])
const trustOptions = computed(() => [
  { value: ALL, label: t('case.timeline.allActors') },
  ...(['VERIFIED', 'UNVERIFIED', 'SYSTEM', 'PLAYER'] as const).map((value) => ({ value, label: t(`case.trust.${value}`) })),
])

/** Every loaded version of each note, oldest first, so an entry knows whether it is the latest. */
const noteVersions = computed(() => {
  const byNote = new Map<string, CaseTimelineItem[]>()
  for (const event of items.value) {
    const id = isNoteVersion(event) ? noteIdOf(event) : null
    if (id) byNote.set(id, [...(byNote.get(id) ?? []), event])
  }
  for (const versions of byNote.values()) versions.sort((a, b) => a.seq - b.seq)
  return byNote
})

function versionsOf(event: CaseTimelineItem): CaseTimelineItem[] {
  const id = isNoteVersion(event) ? noteIdOf(event) : null
  return (id && noteVersions.value.get(id)) || []
}

function isLatest(event: CaseTimelineItem): boolean {
  const versions = versionsOf(event)
  return versions.length > 0 && versions[versions.length - 1]!.seq === event.seq
}

/** The author is whoever added the note; a page that did not load that event falls back to the oldest version it has. */
function canEdit(event: CaseTimelineItem): boolean {
  if (!session.moderator || !isLatest(event) || isRedacted(event)) return false
  if (session.administrator) return true
  const author = versionsOf(event)[0]!.actor
  return author.trust === 'VERIFIED' && author.oid != null && author.oid === session.me?.oid
}

const canRedact = (event: CaseTimelineItem): boolean => session.administrator && isLatest(event) && !isRedacted(event)

function startOfDay(value: string): number | undefined {
  return value ? new Date(`${value}T00:00:00`).getTime() : undefined
}
function endOfDay(value: string): number | undefined {
  return value ? new Date(`${value}T23:59:59.999`).getTime() : undefined
}

let generation = 0

async function load(more: boolean): Promise<void> {
  const mine = ++generation
  if (more) loadingMore.value = true
  else loading.value = true
  const result = await api.caseTimeline(props.reference, {
    cursor: more ? (nextCursor.value ?? undefined) : undefined,
    size: PAGE_SIZE,
    order: 'desc',
    types: group.value === ALL ? undefined : [group.value],
    actorTrust: trust.value === ALL ? undefined : (trust.value as ActorTrust),
    from: startOfDay(from.value),
    to: endOfDay(to.value),
  })
  // A newer request (a filter changed meanwhile) owns the list now.
  if (mine !== generation) return
  loading.value = false
  loadingMore.value = false
  if (!result.ok) {
    error.value = result.error
    return
  }
  error.value = null
  loaded.value = true
  items.value = more ? [...items.value, ...result.data.items] : result.data.items
  nextCursor.value = result.data.nextCursor ?? null
}

const reload = (): Promise<void> => load(false)

async function loadMore(): Promise<void> {
  await load(true)
  await nextTick()
  // The button disappears with the last page; hand focus to the list instead of dropping it on the page.
  if (nextCursor.value === null) list.value?.focus()
}

async function edited(): Promise<void> {
  editing.value = null
  emit('changed', t('case.notes.saved'))
  await reload()
}

async function redacted(message: string): Promise<void> {
  redacting.value = null
  emit('changed', message)
  await reload()
}

watch([group, trust, from, to], () => void reload())
watch(() => props.reference, () => void reload())
void reload()

defineExpose({ reload })
</script>

<template>
  <section :aria-label="t('case.timeline.title')" class="flex flex-col gap-4" data-testid="timeline">
    <h3 class="text-title-medium">{{ t('case.timeline.title') }}</h3>

    <div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-4" role="group" :aria-label="t('case.timeline.filters')">
      <MdSelect v-model="group" :label="t('case.timeline.type')" :options="groupOptions" data-testid="filter-group" />
      <MdSelect v-model="trust" :label="t('case.timeline.actor')" :options="trustOptions" data-testid="filter-trust" />
      <MdTextField v-model="from" type="date" :label="t('case.timeline.from')" data-testid="filter-from" />
      <MdTextField v-model="to" type="date" :label="t('case.timeline.to')" data-testid="filter-to" />
    </div>

    <ResourceState :loading="loading" :error="error" :has-data="loaded" @retry="reload">
      <p role="status" aria-live="polite" class="sr-only" data-testid="timeline-status">{{ t('case.timeline.count', { count: items.length }) }}</p>
      <p v-if="items.length === 0" class="text-body-large text-on-surface-variant" data-testid="timeline-empty">{{ t('case.timeline.empty') }}</p>
      <ol v-else ref="list" tabindex="-1" class="flex flex-col gap-3 outline-none" :aria-busy="loadingMore ? 'true' : 'false'">
        <CaseEventEntry
          v-for="(event, index) in items"
          :key="event.seq"
          class="md-enter"
          :style="{ '--md-i': index % PAGE_SIZE }"
          :event="event"
          :versions="versionsOf(event)"
          :can-edit="canEdit(event)"
          :can-redact="canRedact(event)"
          :editing="editing !== null && editing === noteIdOf(event) && isLatest(event)"
          :save-edit="(text: string) => api.editCaseNote(reference, noteIdOf(event) ?? '', text)"
          @edit="editing = noteIdOf(event)"
          @cancel-edit="editing = null"
          @edited="edited"
          @redact="redacting = noteIdOf(event)"
        />
      </ol>
      <div v-if="nextCursor" class="mt-4">
        <MdButton variant="outlined" :disabled="loadingMore" data-testid="load-more" @click="loadMore">{{ t('case.timeline.loadMore') }}</MdButton>
      </div>
    </ResourceState>

    <RedactNoteDialog :open="redacting !== null" :reference="reference" :note-id="redacting" @close="redacting = null" @done="redacted" />
  </section>
</template>
