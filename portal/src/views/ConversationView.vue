<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { MAX_TEXT_LENGTH, PAGE_SIZE, type AppealConditions, type AppealStatus, type ConversationEntry, type ErrorCode } from '../api/contract'
import ConditionsText from '../components/ConditionsText.vue'
import { formatDate, formatDateTime } from '../format'
import { SESSION_KEY } from '../session'

defineEmits<{ back: [] }>()

/** The conversation is polled, not pushed (design decision 10). */
const POLL_MS = 15_000

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
let polling = false
let timer: ReturnType<typeof setInterval> | undefined
// One key per attempt: a retry after a lost answer is a no-op on the backend, a new text gets a new key.
let attempt: { text: string; key: string } | null = null

const appealState = computed(() => state.punishment?.appeal.state)
// Once the appeal is decided (or the punishment is over) the backend refuses new messages with NO_APPEAL.
// The copy is offered once the appeal is decided; the backend refuses it (NOT_DECIDED) before and for a limited session.
const canDownload = computed(() => state.scope === 'FULL' && !forbidden.value && appeal.value?.decision != null)
const canPost = computed(() => state.scope === 'FULL' && !forbidden.value && appealState.value === 'OPEN')
const length = computed(() => drafts.message.trim().length)
const tooLong = computed(() => length.value > MAX_TEXT_LENGTH)
const blank = computed(() => length.value === 0)

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

function startPolling() {
  stopPolling()
  if (isVisible()) timer = setInterval(() => void poll(), POLL_MS)
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

onMounted(async () => {
  document.addEventListener('visibilitychange', onVisibility)
  startPolling()
  await Promise.all([fetchPage(), refreshAppeal(), loadConditions()])
  loaded.value = true
})

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibility)
  stopPolling()
})

async function send() {
  const text = drafts.message.trim()
  if (blank.value || tooLong.value || sending.value || !canPost.value) return
  if (attempt?.text !== text) attempt = { text, key: crypto.randomUUID() }
  sending.value = true
  failure.value = null
  try {
    const result = await session.api.postMessage(text, attempt.key)
    if (!session.alive(result)) return
    if (result.ok) {
      drafts.message = ''
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

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
    event.preventDefault()
    void send()
  }
}

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

    <div v-if="!forbidden" role="log" aria-live="polite" aria-relevant="additions" :aria-label="t('conversation.thread')">
      <p v-if="loaded && !loadFailed && entries.length === 0" class="text-body-large text-on-surface-variant">{{ t('conversation.empty') }}</p>
      <ol v-else class="flex list-none flex-col gap-3 p-0">
        <li
          v-for="entry in entries"
          :key="entry.seq"
          :data-author="entry.author"
          :class="entry.author === 'PLAYER' ? 'self-end bg-secondary-container text-on-secondary-container' : 'self-start bg-surface-container-high text-on-surface'"
          class="max-w-[85%] rounded-md-lg px-4 py-3"
        >
          <template v-if="entry.kind === 'MESSAGE'">
            <p class="text-label-large">{{ authorLabel(entry) }}</p>
            <p v-if="entry.removed || entry.text === null" class="text-body-large italic">{{ t('conversation.removed') }}</p>
            <p v-else class="plain-text text-body-large">{{ entry.text }}</p>
          </template>
          <p v-else class="text-body-medium italic">{{ markerLabel(entry) }}</p>
          <p class="mt-1 text-body-small opacity-80"><time :datetime="entry.occurredAt">{{ formatDateTime(entry.occurredAt, locale) }}</time></p>
        </li>
      </ol>
    </div>

    <form v-if="canPost" class="flex flex-col gap-3 rounded-md-lg bg-surface-container p-6" novalidate @submit.prevent="send">
      <label for="message" class="text-label-large text-on-surface">{{ t('conversation.composer') }}</label>
      <textarea
        id="message"
        v-model="drafts.message"
        rows="4"
        aria-describedby="message-hint message-count"
        :aria-invalid="tooLong"
        class="plain-text rounded-md-xs border border-outline bg-surface p-4 text-body-large text-on-surface focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
        @keydown="onKeydown"
      />
      <p id="message-hint" class="text-body-small text-on-surface-variant">{{ t('conversation.hint') }}</p>
      <p id="message-count" :class="tooLong ? 'text-error' : 'text-on-surface-variant'" class="text-body-small">
        {{ t('conversation.counter', { count: length, max: MAX_TEXT_LENGTH }) }}
      </p>
      <div role="alert" aria-live="assertive">
        <p v-if="failure" class="rounded-md-sm bg-error-container px-4 py-3 text-body-medium text-on-error-container">{{ failureText }}</p>
      </div>
      <div>
        <button
          type="submit"
          :disabled="blank || tooLong || sending"
          class="md-state inline-flex h-10 items-center justify-center rounded-md-full bg-primary px-6 text-label-large text-on-primary disabled:opacity-60"
        >
          {{ sending ? t('conversation.sending') : t('conversation.send') }}
        </button>
      </div>
    </form>
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
  </section>
</template>
