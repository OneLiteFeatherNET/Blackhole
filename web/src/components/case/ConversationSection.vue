<script setup lang="ts">
// The conversation about the punishment as a thread: what the player wrote in the portal and what
// staff answered. Message text is only ever rendered as plain text (mustache, never v-html).
// Staff with the moderator role can reply, correct and delete their own messages; admins can also
// remove the text of any message or of the whole player side. A viewer reads only.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { ApiError, ApiResult, Case, CaseTimelineItem, NoteRedactionReason } from '../../api/types'
import { formatDateTime } from '../../format'
import { useSessionStore } from '../../stores/session'
import MdButton from '../md/MdButton.vue'
import ResourceState from '../ResourceState.vue'
import StatusPill from '../StatusPill.vue'
import { bannerFor, buildThread, type ThreadMessage } from './conversation'
import MessageComposer from './MessageComposer.vue'
import ParticipantErasure, { type ErasableParticipant } from './ParticipantErasure.vue'
import ReasonDialog from './ReasonDialog.vue'
import TicketStatus from './TicketStatus.vue'
import { buildTickets, participantsOf } from './tickets'

const REDACTION_REASONS: NoteRedactionReason[] = ['PERSONAL_DATA', 'ERASURE_REQUEST', 'INAPPROPRIATE', 'OTHER']

const props = defineProps<{ kase: Case }>()
const emit = defineEmits<{ changed: [message: string] }>()

const { t, locale } = useI18n()
const api = useApi()
const session = useSessionStore()

const events = ref<CaseTimelineItem[]>([])
const loaded = ref(false)
const loading = ref(false)
const error = ref<ApiError | null>(null)
const announcement = ref('')

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

watch(reference, () => void reload())
void reload()
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

/** Staff messages written here only, by their verified author or by an admin; a Discord message is edited in Discord. The backend decides in the end. */
function canChange(message: ThreadMessage): boolean {
  if (!session.moderator || message.author !== 'STAFF' || message.channel === 'DISCORD' || message.deleted || message.redacted) return false
  if (session.administrator) return true
  return message.actor.trust === 'VERIFIED' && message.actor.oid != null && message.actor.oid === session.me?.oid
}

const canRedact = (message: ThreadMessage): boolean => session.administrator && !message.redacted

async function changed(text: string): Promise<void> {
  announcement.value = text
  emit('changed', text)
  await reload()
}

function send(text: string, key: string): Promise<ApiResult<unknown>> {
  return api.postCaseMessage(reference.value, text, key).then(async (result) => {
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

    <ResourceState :loading="loading && !loaded" :error="error" :has-data="loaded" @retry="reload">
      <p role="status" aria-live="polite" class="sr-only" data-testid="conversation-status">{{ announcement }}</p>
      <TicketStatus :tickets="tickets" :reference="reference" :frozen="banner === 'FROZEN'" />
      <p v-if="thread.length === 0" class="text-body-large text-on-surface-variant" data-testid="conversation-empty">{{ t('case.conversation.empty') }}</p>
      <ol v-else class="flex flex-col gap-3" :aria-label="t('case.conversation.thread', { count: thread.length })" data-testid="thread">
        <li
          v-for="message in thread"
          :key="message.id"
          class="flex flex-col gap-1 rounded-md-md border p-4"
          :class="message.author === 'PLAYER' ? 'border-outline-variant bg-surface-container-low' : 'border-outline-variant bg-surface'"
          :data-testid="`message-${message.id}`"
          :data-author="message.author"
        >
          <p class="flex flex-wrap items-center gap-2 text-label-large">
            <span data-testid="message-author">{{ authorName(message) }}</span>
            <StatusPill v-if="message.author === 'PLAYER'" :label="t('case.conversation.fromPlayer')" tone="tertiary" data-testid="from-player" />
            <StatusPill v-if="message.channel === 'DISCORD'" :label="t('case.conversation.discord')" data-testid="from-discord" />
            <StatusPill v-if="discordTrust(message)" :label="t(`case.conversation.trust.${discordTrust(message)}`)" tone="tertiary" data-testid="discord-trust" />
            <StatusPill v-if="message.attachments > 0" :label="t('case.conversation.attachments', { count: message.attachments })" data-testid="attachments" />
            <StatusPill v-if="message.visibility === 'STAFF'" :label="t('case.conversation.staffOnly')" data-testid="staff-only" />
            <StatusPill v-if="message.edits > 0 && !message.deleted && !message.redacted" :label="t('case.conversation.editedMark')" data-testid="edited" />
            <StatusPill v-if="message.deleted && !message.redacted" :label="t('case.conversation.deletedMark')" tone="tertiary" data-testid="deleted" />
            <time class="text-body-small font-normal text-on-surface-variant" :datetime="new Date(message.occurredAt).toISOString()">
              {{ formatDateTime(message.occurredAt, locale) }}
            </time>
          </p>

          <p v-if="message.redacted" class="text-body-medium italic text-on-surface-variant" data-testid="message-removed">{{ t('case.conversation.removed') }}</p>
          <MessageComposer
            v-else-if="editing === message.id"
            :label="t('case.conversation.editLabel')"
            :submit-label="t('case.conversation.save')"
            :initial="message.text ?? ''"
            :save="(text: string) => saveEdit(message, text)"
            cancellable
            @cancel="editing = null"
          />
          <p
            v-else
            class="whitespace-pre-wrap break-words text-body-large"
            :class="message.deleted ? 'text-on-surface-variant line-through' : ''"
            data-testid="message-text-view"
          >{{ message.text }}</p>

          <div v-if="editing !== message.id && (canChange(message) || canRedact(message))" class="mt-1 flex flex-wrap gap-1">
            <template v-if="confirmingDelete === message.id">
              <span class="self-center text-body-medium">{{ t('case.conversation.deleteQuestion') }}</span>
              <MdButton variant="text" data-testid="message-delete-confirm" @click="remove(message)">{{ t('case.conversation.delete') }}</MdButton>
              <MdButton variant="text" data-testid="message-delete-cancel" @click="confirmingDelete = null">{{ t('actions.cancel') }}</MdButton>
            </template>
            <template v-else>
              <MdButton v-if="canChange(message)" variant="text" data-testid="message-edit" @click="editing = message.id">{{ t('case.conversation.edit') }}</MdButton>
              <MdButton v-if="canChange(message)" variant="text" data-testid="message-delete" @click="((confirmingDelete = message.id), (deleteError = ''))">{{ t('case.conversation.delete') }}</MdButton>
              <MdButton v-if="canRedact(message)" variant="text" data-testid="message-redact" @click="redacting = message.id">{{ t('case.conversation.redact') }}</MdButton>
            </template>
          </div>
          <p v-if="deleteError && confirmingDelete === message.id" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container" data-testid="delete-error">{{ deleteError }}</p>
        </li>
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
