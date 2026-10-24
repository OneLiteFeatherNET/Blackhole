<script setup lang="ts">
// One entry of the case timeline: icon and readable text per event type, the actor with a trust
// badge (text and icon, never colour alone), a marker for events rebuilt from earlier data, and for
// notes the latest text with its earlier versions. Unknown types are shown generically.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ApiResult, CaseTimelineItem } from '../../api/types'
import { formatDateTime } from '../../format'
import MdButton from '../md/MdButton.vue'
import StatusPill from '../StatusPill.vue'
import { eventInfo, field, iconPath, isNoteVersion, isRedacted, list, num, text } from './caseEvents'
import EvidenceInline from './EvidenceInline.vue'
import NoteEditor from './NoteEditor.vue'

const props = defineProps<{
  event: CaseTimelineItem
  /** Every loaded version of this event's note, oldest first; empty for other events. */
  versions: CaseTimelineItem[]
  canEdit: boolean
  canRedact: boolean
  editing: boolean
  saveEdit: (text: string) => Promise<ApiResult<unknown>>
}>()
const emit = defineEmits<{ edit: []; cancelEdit: []; edited: []; redact: [] }>()

const { t, te, locale } = useI18n()

const info = computed(() => eventInfo(props.event.type))
const isNote = computed(() => isNoteVersion(props.event))
const redacted = computed(() => isRedacted(props.event))
const latest = computed(() => props.versions.length === 0 || props.versions[props.versions.length - 1]!.seq === props.event.seq)
const earlier = computed(() => props.versions.filter((version) => version.seq < props.event.seq))
const noteText = computed(() => text(field(props.event, 'text')))
const evidenceId = computed(() => (props.event.type.startsWith('evidence.') ? text(field(props.event, 'evidenceId')) : null))

const iso = computed(() => new Date(props.event.occurredAt).toISOString())
const when = computed(() => formatDateTime(props.event.occurredAt, locale.value))

const trust = computed(() => props.event.actor.trust)
// A player is neither verified nor a legacy actor: its own icon, never the verified badge.
const trustIcon = computed(() => iconPath(trust.value === 'VERIFIED' ? 'verified' : trust.value === 'SYSTEM' ? 'system' : trust.value === 'PLAYER' ? 'person' : 'warning'))
const actorName = computed(() => {
  const { name, oid } = props.event.actor
  if (name) return name
  if (oid) return `${oid.slice(0, 8)}…`
  return trust.value === 'SYSTEM' || trust.value === 'PLAYER' ? '' : t('case.actor.unknown')
})

function lookup(prefix: string, value: string | null): string {
  if (value === null) return ''
  return te(`${prefix}.${value}`) ? t(`${prefix}.${value}`) : value
}

/** The one line below the title, built from ids and numbers in the payload only. */
const detail = computed(() => {
  const e = props.event
  const parts: string[] = []
  switch (e.type) {
    case 'punishment.applied': {
      const step = num(field(e, 'stepIndex'))
      const count = num(field(e, 'stepCount'))
      const section = text(field(e, 'section'))
      if (section) parts.push(section)
      if (step !== null && count !== null) parts.push(t('case.detail.step', { step: step + 1, count }))
      const cost = num(field(e, 'eloCost'))
      if (cost !== null) parts.push(t('case.detail.cost', { cost }))
      if (field(e, 'permanent') === true) parts.push(t('player.permanent'))
      if (field(e, 'escalated') === true) parts.push(t('case.detail.escalated'))
      break
    }
    case 'punishment.expired': {
      const at = num(field(e, 'expiredAt'))
      if (at !== null) parts.push(formatDateTime(at, locale.value))
      break
    }
    case 'evidence.attached':
      parts.push(lookup('evidence.kind', text(field(e, 'kind'))))
      break
    case 'appeal.decided':
      parts.push(lookup('appeals.decide.decision', text(field(e, 'decision'))))
      break
    case 'case.status_changed':
      parts.push(t('case.detail.fromTo', { from: lookup('case.status', text(field(e, 'from'))), to: lookup('case.status', text(field(e, 'to'))) }))
      break
    case 'case.priority_changed':
      parts.push(t('case.detail.fromTo', { from: lookup('case.priority', text(field(e, 'from'))), to: lookup('case.priority', text(field(e, 'to'))) }))
      break
    case 'case.assignee_changed': {
      const to = text(field(e, 'to'))
      parts.push(to ? t('case.detail.assignedTo', { who: `${to.slice(0, 8)}…` }) : t('case.detail.unassigned'))
      break
    }
    case 'case.tags_changed': {
      const added = list(field(e, 'added'))
      const removed = list(field(e, 'removed'))
      if (added.length) parts.push(t('case.detail.tagsAdded', { tags: added.join(', ') }))
      if (removed.length) parts.push(t('case.detail.tagsRemoved', { tags: removed.join(', ') }))
      break
    }
    case 'note.redacted':
    case 'message.redacted':
      parts.push(lookup('case.redact.reason', text(field(e, 'reason'))))
      break
    case 'ban_id.leak_marked':
      parts.push(lookup('case.banId.leakReason', text(field(e, 'reason'))))
      if (field(e, 'afterFiling') === true) parts.push(t('case.detail.afterFiling'))
      // The bot found it in a Discord channel: who posted it, never a Discord id (only an opaque participant is stored).
      if (text(field(e, 'source')) === 'DISCORD_BOT') {
        const owner = field(e, 'posterIsOwner')
        parts.push(t('case.detail.foundByBot'))
        parts.push(owner === true ? t('case.detail.posterOwner') : owner === false ? t('case.detail.posterOther') : t('case.detail.posterUnknown'))
      }
      break
    case 'ban_id.reissued':
      parts.push(lookup('case.banId.reissueReason', text(field(e, 'reason'))))
      break
    case 'transcript.exported': {
      const entries = num(field(e, 'entries'))
      parts.push(lookup('case.transcript.format', text(field(e, 'format'))))
      if (entries !== null) parts.push(t('case.detail.entries', { count: entries }))
      break
    }
    case 'protocol.stored': {
      const version = num(field(e, 'version'))
      const entries = num(field(e, 'entries'))
      if (version !== null) parts.push(t('case.tickets.version', { version }))
      if (entries !== null) parts.push(t('case.detail.entries', { count: entries }))
      break
    }
    case 'ticket.opened': {
      const no = num(field(e, 'ticketNo'))
      if (no !== null) parts.push(t('case.tickets.number', { no }))
      break
    }
    case 'ticket.transcript_complete': {
      const count = num(field(e, 'count'))
      if (count !== null) parts.push(t('case.detail.messages', { count }))
      break
    }
    case 'ticket.archived': {
      const version = num(field(e, 'protocolVersion'))
      if (version !== null) parts.push(t('case.tickets.version', { version }))
      break
    }
    case 'ticket.channel_deleted':
      if (field(e, 'transcriptComplete') === false) parts.push(t('case.detail.transcriptIncomplete'))
      break
    default:
      break
  }
  if (field(e, 'approximate') === true) parts.push(t('case.detail.approximate'))
  return parts.filter(Boolean).join(' · ')
})
</script>

<template>
  <li class="flex gap-3 rounded-md-md border border-outline-variant bg-surface p-4" :data-testid="`event-${event.seq}`" :data-type="event.type">
    <svg viewBox="0 0 24 24" class="mt-0.5 h-6 w-6 shrink-0 text-on-surface-variant" aria-hidden="true" data-testid="event-icon" :data-icon="info.icon">
      <path :d="iconPath(info.icon)" fill="currentColor" />
    </svg>
    <div class="min-w-0 flex-1">
      <div class="flex flex-wrap items-center gap-2">
        <h4 class="text-title-small" data-testid="event-title">
          {{ info.known ? t(`case.events.${info.key}`) : t('case.events.unknown') }}
        </h4>
        <code v-if="!info.known" class="rounded-md-xs bg-surface-container-highest px-1 text-label-medium" data-testid="event-type">{{ event.type }}</code>
        <StatusPill v-if="event.origin === 'BACKFILLED'" :label="t('case.origin.BACKFILLED')" tone="tertiary" data-testid="origin" />
        <StatusPill v-else-if="event.origin === 'RECONSTRUCTED'" :label="t('case.origin.RECONSTRUCTED')" tone="tertiary" data-testid="origin" />
        <StatusPill v-if="isNote && latest && versions.length > 1 && !redacted" :label="t('case.notes.edited')" data-testid="edited" />
      </div>

      <p v-if="detail" class="text-body-medium text-on-surface-variant" data-testid="event-detail">{{ detail }}</p>

      <template v-if="isNote">
        <p v-if="redacted" class="text-body-medium italic text-on-surface-variant" data-testid="note-removed">{{ t('case.notes.removed') }}</p>
        <template v-else-if="latest">
          <NoteEditor
            v-if="editing"
            :label="t('case.notes.editLabel')"
            :submit-label="t('case.notes.save')"
            :initial="noteText ?? ''"
            :save="saveEdit"
            cancellable
            @saved="emit('edited')"
            @cancel="emit('cancelEdit')"
          />
          <p v-else class="mt-1 whitespace-pre-wrap break-words text-body-large" data-testid="note-body">{{ noteText }}</p>
          <details v-if="earlier.length > 0 && !editing" class="mt-2 text-body-medium" data-testid="note-history">
            <summary class="cursor-pointer text-primary">{{ t('case.notes.history', { count: earlier.length }) }}</summary>
            <ol class="mt-1 flex flex-col gap-1">
              <li v-for="version in earlier" :key="version.seq" class="rounded-md-xs bg-surface-container-low p-2">
                <time :datetime="new Date(version.occurredAt).toISOString()" class="text-body-small text-on-surface-variant">{{ formatDateTime(version.occurredAt, locale) }}</time>
                <p class="whitespace-pre-wrap break-words">{{ text(field(version, 'text')) }}</p>
              </li>
            </ol>
          </details>
          <div v-if="!editing && (canEdit || canRedact)" class="mt-1 flex gap-1">
            <MdButton v-if="canEdit" variant="text" data-testid="note-edit" @click="emit('edit')">{{ t('case.notes.edit') }}</MdButton>
            <MdButton v-if="canRedact" variant="text" data-testid="note-redact" @click="emit('redact')">{{ t('case.notes.redact') }}</MdButton>
          </div>
        </template>
        <p v-else class="text-body-medium text-on-surface-variant" data-testid="note-superseded">{{ t('case.notes.superseded') }}</p>
      </template>

      <EvidenceInline v-if="evidenceId" :evidence-id="evidenceId" />

      <p class="mt-2 flex flex-wrap items-center gap-x-2 gap-y-1 text-body-small text-on-surface-variant">
        <span class="inline-flex items-center gap-1" :data-testid="`trust-${trust}`" :data-trust="trust">
          <svg viewBox="0 0 24 24" class="h-4 w-4" aria-hidden="true"><path :d="trustIcon" fill="currentColor" /></svg>
          <span class="font-medium">{{ t(`case.trust.${trust}`) }}</span>
        </span>
        <span v-if="actorName" data-testid="actor-name">{{ actorName }}</span>
        <span aria-hidden="true">·</span>
        <time :datetime="iso">{{ when }}</time>
      </p>
    </div>
  </li>
</template>
