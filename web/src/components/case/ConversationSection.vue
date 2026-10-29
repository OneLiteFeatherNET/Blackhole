<script setup lang="ts">
// The conversation about the punishment as a chat: what the player wrote in the portal and what
// staff answered. Message text is drawn from the sanitised block tree with h() (never v-html), and a
// plain text fallback is drawn as text. Staff with the moderator role can reply with files, correct
// and delete their own messages; admins can also remove the text of any message or of the whole
// player side. A viewer reads only. The thread follows the case's event stream and polls as a safety
// net: every 30 s while the stream is open, every 15 s without it.
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { ApiError, ApiResult, CaseTimelineItem, CaseWithSeen, NoteRedactionReason } from '../../api/types'
import type { CaseEvents, CaseEventsState } from '../../api/caseEvents'
import { formatDateTime } from '../../format'
import { useSessionStore } from '../../stores/session'
import MdButton from '../md/MdButton.vue'
import ResourceState from '../ResourceState.vue'
import StatusPill from '../StatusPill.vue'
import CaseLockControl from './CaseLockControl.vue'
import ChatMarkdown, { type ChatNode } from './chat/ChatMarkdown'
import { bannerFor, buildMarkers, buildThread, type ChatFile, type ShareMarker, type ThreadMessage } from './conversation'
import MessageComposer from './MessageComposer.vue'
import ParticipantErasure, { type ErasableParticipant } from './ParticipantErasure.vue'
import ReasonDialog from './ReasonDialog.vue'
import TicketStatus from './TicketStatus.vue'
import { buildTickets, participantsOf } from './tickets'

const REDACTION_REASONS: NoteRedactionReason[] = ['PERSONAL_DATA', 'ERASURE_REQUEST', 'INAPPROPRIATE', 'OTHER']
/** Polling is the safety net: with a stream it is slow, without one it is what keeps the thread fresh. */
const POLL_WITH_STREAM_MS = 30_000
const POLL_WITHOUT_STREAM_MS = 15_000

const props = defineProps<{ kase: CaseWithSeen }>()
const emit = defineEmits<{ changed: [message: string] }>()

const { t, locale } = useI18n()
const api = useApi()
const session = useSessionStore()

const events = ref<CaseTimelineItem[]>([])
const loaded = ref(false)
const loading = ref(false)
const error = ref<ApiError | null>(null)
const announcement = ref('')
const seenSeq = ref(props.kase.playerSeenSeq ?? 0)
const liveState = ref<CaseEventsState>('connecting')

const editing = ref<string | null>(null)
const confirmingDelete = ref<string | null>(null)
const deleteError = ref('')
const redacting = ref<string | null>(null)
const erasing = ref(false)

const reference = computed(() => props.kase.punishmentIdentifier)
const thread = computed(() => buildThread(events.value))
const banner = computed(() => bannerFor(props.kase.banIdLeakedAt, events.value))
const tickets = computed(() => buildTickets(events.value))
const participants = computed<ErasableParticipant[]>(() =>
  participantsOf(thread.value).map((id) => {
    const first = thread.value.find((message) => message.participantId === id)
    return { id, role: first ? t(`case.conversation.role.${first.author}`) : '' }
  }),
)
const hasPlayerText = computed(() => thread.value.some((message) => message.author === 'PLAYER' && !message.redacted))

/** Share markers go in front of the first message that comes after them; the rest trail the thread. */
const layout = computed(() => {
  const markers = buildMarkers(events.value)
  let index = 0
  const blocks = thread.value.map((message) => {
    const before: ShareMarker[] = []
    while (index < markers.length && markers[index]!.seq < message.firstSeq) before.push(markers[index++]!)
    return { message, markers: before }
  })
  return { blocks, tail: markers.slice(index) }
})

/** The last staff message the player has read: "Seen" is shown under it. */
const seenId = computed(() => {
  let found: string | null = null
  for (const message of thread.value) if (message.author === 'STAFF' && message.firstSeq <= seenSeq.value) found = message.id
  return found
})

let generation = 0

async function reload(): Promise<void> {
  const mine = ++generation
  loading.value = true
  const result = await api.caseConversation(reference.value)
  // A newer reload (or another case) owns the thread now.
  if (mine !== generation) return
  loading.value = false
  if (!result.ok) {
    error.value = result.error
    return
  }
  error.value = null
  loaded.value = true
  events.value = result.data
}

async function refreshAfterChange(): Promise<void> {
  await reload()
  const detail = await api.caseDetail(reference.value)
  if (detail.ok) seenSeq.value = (detail.data as CaseWithSeen).playerSeenSeq ?? seenSeq.value
}

// Live updates: a stream when the browser can hold one, and polling that is slower while it does.
let stream: CaseEvents | null = null
let poll: ReturnType<typeof setInterval> | undefined

function schedulePoll(): void {
  if (poll !== undefined) clearInterval(poll)
  const period = liveState.value === 'open' ? POLL_WITH_STREAM_MS : POLL_WITHOUT_STREAM_MS
  poll = setInterval(() => {
    if (document.visibilityState !== 'hidden') void reload()
  }, period)
}

function connect(): void {
  stream?.close()
  stream = api.openCaseEvents(reference.value, {
    onChanged: () => void refreshAfterChange(),
    onState: (state) => {
      const wasOpen = liveState.value === 'open'
      liveState.value = state
      if ((state === 'open') !== wasOpen) schedulePoll()
    },
  })
}

watch(reference, () => {
  connect()
  void reload()
})
connect()
schedulePoll()
void reload()

watch(
  () => props.kase.playerSeenSeq,
  (seq) => (seenSeq.value = seq ?? seenSeq.value),
)

onBeforeUnmount(() => {
  stream?.close()
  stream = null
  if (poll !== undefined) clearInterval(poll)
})

defineExpose({ reload })

function authorName(message: ThreadMessage): string {
  if (message.author === 'PLAYER') return t('case.conversation.player')
  if (message.author === 'BOT') return t('case.conversation.role.BOT')
  if (message.author === 'EXTERNAL') return t('case.conversation.role.EXTERNAL')
  const { name, oid } = message.actor
  if (name) return name
  return oid ? `${oid.slice(0, 8)}…` : t('case.conversation.staff')
}

/** The Discord side a message came from, for staff, bot and unknown authors; the player's own pill says it already. */
function discordTrust(message: ThreadMessage): string | null {
  if (message.channel !== 'DISCORD' || message.author === 'PLAYER') return null
  if (message.author === 'STAFF') return message.actor.trust === 'VERIFIED' ? 'verified' : 'unverified'
  return message.author
}

/** Staff messages sit on the right, the player's on the left; Discord and bot messages with staff. */
const sideOf = (message: ThreadMessage): 'start' | 'end' => (message.author === 'PLAYER' ? 'start' : 'end')

function sizeOf(bytes: number): string {
  return bytes >= 1024 * 1024 ? `${(bytes / (1024 * 1024)).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`
}

function markerText(marker: ShareMarker): string {
  if (marker.kind === 'WITHDRAWN') return t('case.conversation.withdrawn')
  if (marker.mode === 'FILE') return `${t('case.conversation.shared')}: ${t('case.conversation.sharedImage')}`
  if (marker.lineCount !== null) return `${t('case.conversation.shared')}: ${t('case.conversation.sharedLines', { n: marker.lineCount })}`
  return t('case.conversation.shared')
}

function fileProvenance(file: ChatFile): boolean {
  return file.provenance === 'PLAYER'
}

/** Staff messages written here only, by their verified author or by an admin; a Discord message is edited in Discord. The backend decides in the end. */
function canChange(message: ThreadMessage): boolean {
  if (!session.moderator || message.author !== 'STAFF' || message.channel === 'DISCORD' || message.deleted || message.redacted) return false
  if (session.administrator) return true
  return message.actor.trust === 'VERIFIED' && message.actor.oid != null && message.actor.oid === session.me?.oid
}

const canRedact = (message: ThreadMessage): boolean => session.administrator && !message.redacted

/** Files are staged with the server as they are chosen; a moderator sends them with the reply. */
const uploadFile = (file: File) => api.uploadCaseAttachment(reference.value, file)

async function changed(text: string): Promise<void> {
  announcement.value = text
  emit('changed', text)
  await reload()
}

function send(text: string, key: string, attachmentIds: string[]): Promise<ApiResult<unknown>> {
  const request =
    attachmentIds.length > 0
      ? api.postCaseMessage(reference.value, text, key, attachmentIds)
      : api.postCaseMessage(reference.value, text, key)
  return request.then(async (result) => {
    if (result.ok) await changed(t('case.conversation.sent'))
    return result
  })
}

async function saveEdit(message: ThreadMessage, text: string): Promise<ApiResult<unknown>> {
  const result = await api.editCaseMessage(reference.value, message.id, text)
  if (result.ok) {
    editing.value = null
    await changed(t('case.conversation.edited'))
  }
  return result
}

async function remove(message: ThreadMessage): Promise<void> {
  deleteError.value = ''
  const result = await api.deleteCaseMessage(reference.value, message.id)
  if (!result.ok) {
    deleteError.value = result.error.message
    return
  }
  confirmingDelete.value = null
  await changed(t('case.conversation.deleted'))
}

async function redacted(message: string): Promise<void> {
  redacting.value = null
  erasing.value = false
  await changed(message)
}
</script>

<template>
  <section :aria-label="t('case.conversation.title')" class="flex flex-col gap-3" data-testid="conversation">
    <h3 class="text-title-medium">{{ t('case.conversation.title') }}</h3>

    <p v-if="banner === 'FORFEITED'" role="status" class="rounded-md-md bg-error-container p-3 text-on-error-container" data-testid="banner-forfeited">
      {{ t('case.conversation.banner.forfeited') }}
    </p>
    <p v-else-if="banner === 'FROZEN'" role="status" class="rounded-md-md bg-error-container p-3 text-on-error-container" data-testid="banner-frozen">
      {{ t('case.conversation.banner.frozen') }}
    </p>
    <p v-if="liveState === 'reconnecting'" role="status" class="text-body-small text-on-surface-variant" data-testid="live-paused">
      {{ t('case.conversation.reconnecting') }}
    </p>

    <ResourceState :loading="loading && !loaded" :error="error" :has-data="loaded" @retry="reload">
      <p role="status" aria-live="polite" class="sr-only" data-testid="conversation-status">{{ announcement }}</p>
      <TicketStatus :tickets="tickets" :reference="reference" :frozen="banner === 'FROZEN'" />
      <CaseLockControl
        v-if="tickets.length > 0"
        :reference="reference"
        :tickets="tickets"
        :assignee-name="kase.assigneeName ?? null"
        :leak-frozen="banner === 'FROZEN'"
        @changed="changed"
      />
      <p v-if="thread.length === 0 && layout.tail.length === 0" class="text-body-large text-on-surface-variant" data-testid="conversation-empty">{{ t('case.conversation.empty') }}</p>
      <ol
        v-else
        class="flex flex-col gap-3"
        role="log"
        aria-live="polite"
        :aria-label="t('case.conversation.thread', { count: thread.length })"
        data-testid="thread"
      >
        <template v-for="block in layout.blocks" :key="block.message.id">
          <li
            v-for="marker in block.markers"
            :key="marker.key"
            class="self-center text-body-small text-on-surface-variant"
            data-testid="marker"
            :data-kind="marker.kind"
          >{{ markerText(marker) }}</li>

          <li
            class="flex max-w-[88%] flex-col gap-2 rounded-md-md border p-4"
            :class="[
              block.message.author === 'PLAYER' ? 'self-start border-outline-variant bg-surface-container-low' : 'self-end border-outline-variant bg-surface',
            ]"
            :data-testid="`message-${block.message.id}`"
            :data-author="block.message.author"
            :data-side="sideOf(block.message)"
          >
            <p class="flex flex-wrap items-center gap-2 text-label-large">
              <span data-testid="message-author">{{ authorName(block.message) }}</span>
              <StatusPill v-if="block.message.author === 'PLAYER'" :label="t('case.conversation.fromPlayer')" tone="tertiary" data-testid="from-player" />
              <StatusPill v-if="block.message.channel === 'DISCORD'" :label="t('case.conversation.discord')" data-testid="from-discord" />
              <StatusPill
                v-if="discordTrust(block.message)"
                :label="t(`case.conversation.trust.${discordTrust(block.message)}`)"
                tone="tertiary"
                data-testid="discord-trust"
              />
              <StatusPill v-if="block.message.attachments > 0" :label="t('case.conversation.attachments', { count: block.message.attachments })" data-testid="attachments" />
              <StatusPill v-if="block.message.visibility === 'STAFF'" :label="t('case.conversation.staffOnly')" data-testid="staff-only" />
              <StatusPill v-if="block.message.edits > 0 && !block.message.deleted && !block.message.redacted" :label="t('case.conversation.editedMark')" data-testid="edited" />
              <StatusPill v-if="block.message.deleted && !block.message.redacted" :label="t('case.conversation.deletedMark')" tone="tertiary" data-testid="deleted" />
              <time class="text-body-small font-normal text-on-surface-variant" :datetime="new Date(block.message.occurredAt).toISOString()">
                {{ formatDateTime(block.message.occurredAt, locale) }}
              </time>
            </p>

            <p v-if="block.message.redacted" class="text-body-medium italic text-on-surface-variant" data-testid="message-removed">{{ t('case.conversation.removed') }}</p>
            <MessageComposer
              v-else-if="editing === block.message.id"
              :label="t('case.conversation.editLabel')"
              :submit-label="t('case.conversation.save')"
              :initial="block.message.text ?? ''"
              :save="(text: string) => saveEdit(block.message, text)"
              cancellable
              @cancel="editing = null"
            />
            <div
              v-else-if="block.message.blocks"
              :class="block.message.deleted ? 'text-on-surface-variant line-through' : ''"
              class="text-body-large"
              data-testid="message-text-view"
            >
              <ChatMarkdown :nodes="(block.message.blocks as ChatNode[])" />
            </div>
            <p
              v-else
              class="whitespace-pre-wrap break-words text-body-large"
              :class="block.message.deleted ? 'text-on-surface-variant line-through' : ''"
              data-testid="message-text-view"
            >{{ block.message.text }}</p>

            <ul v-if="block.message.files.length > 0" class="flex flex-col gap-2" data-testid="files">
              <li
                v-for="file in block.message.files"
                :key="file.attachmentId"
                class="flex flex-wrap items-center gap-2 rounded-md-md border border-outline-variant p-2 text-body-medium"
                data-testid="file"
              >
                <span class="break-all font-medium">{{ file.name }}</span>
                <span class="text-body-small text-on-surface-variant">{{ sizeOf(file.size) }}</span>
                <StatusPill v-if="fileProvenance(file)" :label="t('case.conversation.playerFile')" tone="tertiary" data-testid="file-provenance" />
              </li>
            </ul>

            <div v-if="editing !== block.message.id && (canChange(block.message) || canRedact(block.message))" class="mt-1 flex flex-wrap gap-1">
              <template v-if="confirmingDelete === block.message.id">
                <span class="self-center text-body-medium">{{ t('case.conversation.deleteQuestion') }}</span>
                <MdButton variant="text" data-testid="message-delete-confirm" @click="remove(block.message)">{{ t('case.conversation.delete') }}</MdButton>
                <MdButton variant="text" data-testid="message-delete-cancel" @click="confirmingDelete = null">{{ t('actions.cancel') }}</MdButton>
              </template>
              <template v-else>
                <MdButton v-if="canChange(block.message)" variant="text" data-testid="message-edit" @click="editing = block.message.id">{{ t('case.conversation.edit') }}</MdButton>
                <MdButton
                  v-if="canChange(block.message)"
                  variant="text"
                  data-testid="message-delete"
                  @click="((confirmingDelete = block.message.id), (deleteError = ''))"
                >{{ t('case.conversation.delete') }}</MdButton>
                <MdButton v-if="canRedact(block.message)" variant="text" data-testid="message-redact" @click="redacting = block.message.id">{{ t('case.conversation.redact') }}</MdButton>
              </template>
            </div>
            <p v-if="deleteError && confirmingDelete === block.message.id" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container" data-testid="delete-error">{{ deleteError }}</p>
          </li>

          <li v-if="seenId === block.message.id" class="self-end text-body-small text-on-surface-variant" data-testid="seen">{{ t('case.conversation.seen') }}</li>
        </template>

        <li
          v-for="marker in layout.tail"
          :key="marker.key"
          class="self-center text-body-small text-on-surface-variant"
          data-testid="marker"
          :data-kind="marker.kind"
        >{{ markerText(marker) }}</li>
      </ol>

      <div v-if="session.administrator && hasPlayerText" class="mt-1">
        <MdButton variant="outlined" data-testid="erase-player" @click="erasing = true">{{ t('case.conversation.erasePlayer') }}</MdButton>
      </div>
      <ParticipantErasure v-if="session.administrator" :reference="reference" :participants="participants" @done="changed" />
    </ResourceState>

    <MessageComposer
      v-if="session.moderator"
      :label="t('case.conversation.newLabel')"
      :submit-label="t('case.conversation.send')"
      :save="send"
      :upload="uploadFile"
    />
    <p v-else class="text-body-medium text-on-surface-variant" data-testid="conversation-read-only">{{ t('case.conversation.readOnly') }}</p>

    <ReasonDialog
      :open="redacting !== null"
      name="redact-message"
      :headline="t('case.conversation.redactTitle')"
      :body="t('case.conversation.redactBody')"
      :confirm-label="t('case.conversation.redactConfirm')"
      :reasons="REDACTION_REASONS"
      reason-prefix="case.redact.reason"
      :run="(reason: string) => api.redactCaseMessage(reference, redacting ?? '', reason as NoteRedactionReason)"
      @close="redacting = null"
      @done="redacted(t('case.conversation.redactDone'))"
    />
    <ReasonDialog
      :open="erasing"
      name="erase-player"
      :headline="t('case.conversation.eraseTitle')"
      :body="t('case.conversation.eraseBody')"
      :confirm-label="t('case.conversation.eraseConfirm')"
      :reasons="REDACTION_REASONS"
      reason-prefix="case.redact.reason"
      :run="(reason: string) => api.eraseCasePlayerMessages(reference, reason as NoteRedactionReason)"
      @close="erasing = false"
      @done="redacted(t('case.conversation.eraseDone'))"
    />
  </section>
</template>
