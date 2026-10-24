<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { MAX_TEXT_LENGTH, PAGE_SIZE, type AppealConditions, type AppealStatus, type ConversationEntry, type ErrorCode } from '../api/contract'
import { EVENTS_URL } from '../api/portal'
import ChatComposer from '../components/ChatComposer.vue'
import ChatLightbox from '../components/ChatLightbox.vue'
import ConditionsText from '../components/ConditionsText.vue'
import MessageBubble from '../components/MessageBubble.vue'
import { releasePreview } from '../chat/files'
import { openLive, type LiveHandle, type LiveState } from '../chat/live'
import { formatDate, formatDateTime } from '../format'
import { SESSION_KEY } from '../session'

defineEmits<{ back: [] }>()

/** Polling cadence: 15 s without a live stream, 30 s as a safety net beside one (design decision 10). */
const POLL_MS = 15_000
const STREAM_POLL_MS = 30_000

const { t, te, locale } = useI18n()
const session = inject(SESSION_KEY)!
const state = session.state
const drafts = state.drafts

const entries = ref<ConversationEntry[]>([])
const appeal = ref<AppealStatus | null>(null)
/** The conditions stay readable here; if they cannot be loaded the block is simply left out. */
const conditions = ref<AppealConditions | null>(null)
const cursor = ref<number | null>(null)
const loaded = ref(false)
const loadFailed = ref(false)
/** A limited session is refused the conversation (403): shown as a notice, not as an error. */
const forbidden = ref(false)
const sending = ref(false)
const failure = ref<ErrorCode | null>(null)
const streamState = ref<LiveState>('closed')
const image = ref<{ src: string; name: string } | null>(null)
let polling = false
let timer: ReturnType<typeof setInterval> | undefined
let live: LiveHandle | undefined
// One key per attempt: a retry after a lost answer is a no-op on the backend, a new text or new files get a new key.
let attempt: { signature: string; key: string } | null = null

const appealState = computed(() => state.punishment?.appeal.state)
// Once the appeal is decided (or the punishment is over) the backend refuses new messages with NO_APPEAL.
// The copy is offered once the appeal is decided; the backend refuses it (NOT_DECIDED) before and for a limited session.
const canDownload = computed(() => state.scope === 'FULL' && !forbidden.value && appeal.value?.decision != null)
const canPost = computed(() => state.scope === 'FULL' && !forbidden.value && appealState.value === 'OPEN')

function merge(incoming: ConversationEntry[]) {
  const next = [...entries.value]
  for (const entry of incoming) {
    const bySeq = next.findIndex((existing) => existing.seq === entry.seq)
    const byMessage = entry.messageId ? next.findIndex((existing) => existing.messageId === entry.messageId) : -1
    const at = bySeq !== -1 ? bySeq : byMessage
    if (at === -1) next.push(entry)
    else next[at] = entry
  }
  entries.value = next.sort((a, b) => a.seq - b.seq)
}

/** Reads from the cursor; a full page means there may be more, so the next page is read at once. */
async function fetchPage() {
  if (state.scope === 'LIMITED') {
    forbidden.value = true
    return
  }
  for (let pages = 0; pages < 25; pages++) {
    const result = await session.api.conversation(cursor.value)
    if (!session.alive(result)) return
    if (!result.ok) {
      if (result.error.code === 'FORBIDDEN') forbidden.value = true
      else loadFailed.value = true
      return
    }
    loadFailed.value = false
    merge(result.data.entries)
    if (result.data.cursor !== null) cursor.value = result.data.cursor
    if (result.data.entries.length < PAGE_SIZE) return
  }
}

async function refreshAppeal() {
  if (state.scope === 'LIMITED' || forbidden.value) return
  const result = await session.api.appeal()
  if (!session.alive(result)) return
  appeal.value = result.ok ? result.data : null
}

async function loadConditions() {
  const result = await session.api.appealConditions()
  if (!session.alive(result)) return
  if (result.ok) conditions.value = result.data
}

async function poll() {
  if (polling) return
  polling = true
  try {
    await fetchPage()
    await session.refreshPunishment()
    // The decision is read once the facts say the appeal was decided.
    if (appeal.value?.decision == null && appealState.value === 'DECIDED') await refreshAppeal()
  } finally {
    polling = false
  }
}

function isVisible() {
  return !document.hidden
}

function pollInterval() {
  return streamState.value === 'open' ? STREAM_POLL_MS : POLL_MS
}

function startPolling() {
  stopPolling()
  if (isVisible()) timer = setInterval(() => void poll(), pollInterval())
}

function stopPolling() {
  if (timer !== undefined) clearInterval(timer)
  timer = undefined
}

function onVisibility() {
  if (isVisible()) {
    void poll()
    startPolling()
  } else {
    stopPolling()
  }
}

/** The live signal says only that something changed: the page reads it with its cursor. */
function openStream() {
  if (state.scope !== 'FULL' || typeof EventSource === 'undefined') return
  live = openLive({
    url: EVENTS_URL,
    createSource: (url) => new EventSource(url),
    onChanged: () => void poll(),
    onExpired: () => session.expire(),
    onState: (next) => {
      streamState.value = next
      // The cadence follows the stream: a 30 s safety poll while it is open, 15 s otherwise.
      if (isVisible() && timer !== undefined) startPolling()
    },
  })
}

onMounted(async () => {
  document.addEventListener('visibilitychange', onVisibility)
  startPolling()
  openStream()
  await Promise.all([fetchPage(), refreshAppeal(), loadConditions()])
  loaded.value = true
})

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibility)
  stopPolling()
  live?.close()
  live = undefined
})

/** The text and the staged files a send would carry; nothing is sent while a file is still uploading. */
function payload() {
  const text = drafts.message.trim()
  const files = drafts.files.filter((file) => file.state === 'staged').map((file) => file.attachmentId!)
  return { text, files }
}

async function send() {
  const { text, files } = payload()
  const pending = drafts.files.some((file) => file.state === 'uploading')
  if ((text.length === 0 && files.length === 0) || text.length > MAX_TEXT_LENGTH || pending || sending.value || !canPost.value) return
  const signature = JSON.stringify([text, files])
  if (attempt?.signature !== signature) attempt = { signature, key: crypto.randomUUID() }
  sending.value = true
  failure.value = null
  try {
    // A text-only message is sent exactly as before; files add their ids, and the text may then be absent.
    const result =
      files.length > 0
        ? await session.api.postMessage(text.length > 0 ? text : undefined, attempt.key, files)
        : await session.api.postMessage(text, attempt.key)
    if (!session.alive(result)) return
    if (result.ok) {
      drafts.message = ''
      for (const file of drafts.files) releasePreview(file)
      drafts.files = []
      attempt = null
      await fetchPage()
      return
    }
    failure.value = result.error.code
    if (['FROZEN', 'FORFEITED', 'NO_APPEAL', 'CONFLICT'].includes(result.error.code)) await session.refreshPunishment()
  } finally {
    sending.value = false
  }
}

/** Rows of the thread: a bubble per message (grouped under the same author), markers, and a line per day. */
interface Row {
  key: string
  kind: 'day' | 'bubble' | 'marker'
  entry?: ConversationEntry
  grouped?: boolean
  label?: string
}

const rows = computed<Row[]>(() => {
  const out: Row[] = []
  let day = ''
  let previous: ConversationEntry | undefined
  for (const entry of entries.value) {
    const current = entry.occurredAt.slice(0, 10)
    const newDay = current !== day
    if (newDay) {
      day = current
      out.push({ key: `day-${entry.seq}`, kind: 'day', label: formatDate(entry.occurredAt, locale.value) })
      previous = undefined
    }
    const bubble = entry.kind === 'MESSAGE' || entry.share != null || (entry.attachments?.length ?? 0) > 0
    if (bubble) {
      const grouped = !newDay && previous !== undefined && previous.author === entry.author && previous.kind === 'MESSAGE' && entry.kind === 'MESSAGE'
      out.push({ key: `seq-${entry.seq}`, kind: 'bubble', entry, grouped })
      previous = entry
    } else {
      out.push({ key: `seq-${entry.seq}`, kind: 'marker', entry, label: markerLabel(entry) })
      previous = undefined
    }
  }
  return out
})

function authorLabel(entry: ConversationEntry) {
  return t(`conversation.author.${entry.author}`)
}

function markerLabel(entry: ConversationEntry) {
  return te(`conversation.marker.${entry.kind}`) ? t(`conversation.marker.${entry.kind}`) : t('conversation.marker.OTHER')
}

const decisionLabel = computed(() => {
  const decision = appeal.value?.decision
  if (!decision) return ''
  return te(`conversation.decision.${decision}`) ? t(`conversation.decision.${decision}`) : t('conversation.decision.OTHER')
})

const failureText = computed(() => {
  if (!failure.value) return ''
  if (failure.value === 'FILES_UNAVAILABLE') return t('chat.errors.FILES_UNAVAILABLE')
  return te(`conversation.errors.${failure.value}`) ? t(`conversation.errors.${failure.value}`) : t('conversation.errors.UNAVAILABLE')
})
</script>

<template>
  <section aria-labelledby="conversation-title" class="flex flex-col gap-6">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <h1 id="conversation-title" class="text-headline-small text-on-surface">{{ t('conversation.title') }}</h1>
      <button
        type="button"
        class="md-state inline-flex h-10 items-center justify-center rounded-md-full px-6 text-label-large text-primary"
        @click="$emit('back')"
      >
        {{ t('common.back') }}
      </button>
    </div>

    <details v-if="conditions" class="rounded-md-lg bg-surface-container px-6 py-4" data-testid="conditions-block">
      <summary class="cursor-pointer text-title-medium text-on-surface">{{ t('conversation.conditions') }}</summary>
      <div class="mt-3"><ConditionsText :text="conditions.text" /></div>
    </details>

    <div v-if="appeal?.decision" class="rounded-md-lg bg-primary-container p-6 text-on-primary-container">
      <h2 class="text-title-large">{{ t('conversation.decisionTitle') }}</h2>
      <p class="mt-2 text-body-large">{{ decisionLabel }}</p>
      <p v-if="appeal.newExpiresAt" class="mt-1 text-body-large">
        {{ t('conversation.newExpiry', { date: formatDate(appeal.newExpiresAt, locale) }) }}
      </p>
      <p class="mt-3 text-body-medium">{{ t('conversation.decisionHint') }}</p>
    </div>

    <p v-if="appealState === 'FROZEN'" class="rounded-md-sm bg-tertiary-container px-4 py-3 text-body-medium text-on-tertiary-container">
      {{ t('notice.frozen') }}
    </p>

    <p v-if="!loaded" role="status" class="text-body-large text-on-surface-variant">{{ t('app.loading') }}</p>
    <p v-else-if="forbidden" class="rounded-md-sm bg-tertiary-container px-4 py-3 text-body-medium text-on-tertiary-container">
      {{ t('notice.limited') }}
    </p>
    <p v-else-if="loadFailed" role="alert" class="rounded-md-sm bg-error-container px-4 py-3 text-body-medium text-on-error-container">
      {{ t('conversation.loadFailed') }}
    </p>
    <p v-else-if="streamState === 'reconnecting'" role="status" class="text-body-small text-on-surface-variant">{{ t('chat.live.reconnecting') }}</p>

    <div v-if="!forbidden" role="log" aria-live="polite" aria-relevant="additions" :aria-label="t('conversation.thread')">
      <p v-if="loaded && !loadFailed && entries.length === 0" class="text-body-large text-on-surface-variant">{{ t('conversation.empty') }}</p>
      <ol v-else class="flex list-none flex-col gap-3 p-0">
        <template v-for="row in rows" :key="row.key">
          <li v-if="row.kind === 'day'" role="presentation" class="self-center text-label-medium text-on-surface-variant">
            <p>{{ row.label }}</p>
          </li>
          <li v-else-if="row.kind === 'marker'" role="presentation" class="self-center text-body-medium italic text-on-surface-variant">
            <p>{{ row.label }} · <time :datetime="row.entry!.occurredAt">{{ formatDateTime(row.entry!.occurredAt, locale) }}</time></p>
          </li>
          <li
            v-else
            :data-author="row.entry!.author"
            :class="row.entry!.author === 'PLAYER' ? 'self-end' : 'self-start'"
            class="max-w-[85%]"
          >
            <MessageBubble
              :entry="row.entry!"
              :author-label="authorLabel(row.entry!)"
              :own="row.entry!.author === 'PLAYER'"
              :grouped="row.grouped === true"
              @image="(src, name) => (image = { src, name })"
            />
          </li>
        </template>
      </ol>
    </div>

    <ChatComposer v-if="canPost" :sending="sending" :failure="failureText" @send="send" />
    <p v-else-if="loaded && !forbidden && appealState !== 'FROZEN'" class="text-body-medium text-on-surface-variant">{{ t('conversation.readOnly') }}</p>

    <p v-if="canDownload" class="text-body-medium">
      <a
        href="/api/appeal/protocol?format=text"
        download
        class="text-primary underline underline-offset-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
      >
        {{ t('conversation.downloadText') }}
      </a>
      <span aria-hidden="true"> · </span>
      <a
        href="/api/appeal/protocol?format=json"
        download
        class="text-primary underline underline-offset-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
      >
        {{ t('conversation.downloadJson') }}
      </a>
    </p>

    <ChatLightbox :src="image?.src ?? null" :name="image?.name ?? ''" @close="image = null" />
  </section>
</template>
