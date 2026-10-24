<script setup lang="ts">
// The Discord tickets of the case: number, state, whether the transcript is verified complete and which
// protocol version is stored, with banners when the record may be incomplete or the protocol is missing.
// Everything comes from case events; no Discord id is ever shown or even loaded here.
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { CaseProtocolVersion } from '../../api/types'
import { formatDateTime } from '../../format'
import MdButton from '../md/MdButton.vue'
import StatusPill from '../StatusPill.vue'
import type { TicketPhase, TicketView } from './tickets'

const props = defineProps<{ tickets: TicketView[]; reference: string; frozen: boolean }>()

const { t, locale } = useI18n()
const api = useApi()

const protocols = ref<CaseProtocolVersion[] | null>(null)

/** The stored protocols with open and download live in ProtocolSection further down the page. */
function goToProtocols() {
  document.getElementById('case-protocol')?.scrollIntoView({ block: 'start' })
}
const protocolError = ref('')
const loadingProtocols = ref(false)
const announcement = ref('')

const TONES: Record<TicketPhase, 'neutral' | 'primary' | 'tertiary'> = {
  OPEN: 'primary',
  CLOSING: 'tertiary',
  CLOSED: 'neutral',
  ARCHIVED: 'neutral',
  DELETED: 'neutral',
}

async function showProtocols(): Promise<void> {
  if (loadingProtocols.value) return
  loadingProtocols.value = true
  protocolError.value = ''
  const result = await api.caseProtocols(props.reference)
  loadingProtocols.value = false
  if (!result.ok) {
    protocolError.value = result.error.message
    return
  }
  protocols.value = result.data
  announcement.value = t('case.tickets.protocolsLoaded', { count: result.data.length })
}
</script>

<template>
  <section v-if="tickets.length > 0" :aria-label="t('case.tickets.title')" class="flex flex-col gap-3" data-testid="tickets">
    <h3 class="text-title-medium">{{ t('case.tickets.title') }}</h3>

    <article
      v-for="ticket in tickets"
      :key="ticket.ticketId"
      class="flex flex-col gap-2 rounded-md-md border border-outline-variant bg-surface p-4"
      :data-testid="`ticket-${ticket.ticketNo}`"
      :data-phase="ticket.phase"
    >
      <h4 class="flex flex-wrap items-center gap-2 text-title-small">
        <span>{{ t('case.tickets.number', { no: ticket.ticketNo }) }}</span>
        <StatusPill :label="t(`case.tickets.phase.${ticket.phase}`)" :tone="TONES[ticket.phase]" data-testid="ticket-state" />
        <StatusPill v-if="frozen && ticket.phase !== 'ARCHIVED' && ticket.phase !== 'DELETED'" :label="t('case.tickets.frozen')" tone="error" data-testid="ticket-frozen" />
      </h4>

      <dl class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-body-medium">
        <dt class="text-on-surface-variant">{{ t('case.tickets.opened') }}</dt>
        <dd>
          <time :datetime="new Date(ticket.openedAt).toISOString()">{{ formatDateTime(ticket.openedAt, locale) }}</time>
        </dd>
        <dt class="text-on-surface-variant">{{ t('case.tickets.transcript') }}</dt>
        <dd data-testid="ticket-transcript">{{ ticket.transcriptComplete ? t('case.tickets.complete') : t('case.tickets.notComplete') }}</dd>
        <dt class="text-on-surface-variant">{{ t('case.tickets.protocol') }}</dt>
        <dd data-testid="ticket-protocol">
          {{ ticket.protocolVersion !== null ? t('case.tickets.version', { version: ticket.protocolVersion }) : t('case.tickets.noProtocol') }}
        </dd>
      </dl>

      <p
        v-if="ticket.incompleteSince !== null"
        role="status"
        class="rounded-md-md bg-error-container p-3 text-on-error-container"
        data-testid="ticket-incomplete"
      >
        {{ t(ticket.channelLostEarly ? 'case.tickets.banner.lost' : 'case.tickets.banner.incomplete', { since: formatDateTime(ticket.incompleteSince, locale) }) }}
      </p>
      <p
        v-if="ticket.protocolMissing"
        role="status"
        class="rounded-md-md bg-error-container p-3 text-on-error-container"
        data-testid="ticket-protocol-missing"
      >
        {{ t('case.tickets.banner.protocolMissing') }}
      </p>
    </article>

    <div class="flex flex-col gap-2">
      <MdButton variant="outlined" class="self-start" data-testid="protocols-show" :disabled="loadingProtocols" @click="showProtocols">
        {{ t('case.tickets.showProtocols') }}
      </MdButton>
      <p role="status" aria-live="polite" class="sr-only" data-testid="protocols-status">{{ announcement }}</p>
      <p v-if="protocolError" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container" data-testid="protocols-error">{{ protocolError }}</p>
      <template v-if="protocols">
        <p v-if="protocols.length === 0" class="text-body-medium text-on-surface-variant" data-testid="protocols-empty">{{ t('case.tickets.noProtocols') }}</p>
        <ul v-else class="flex flex-col gap-1 text-body-medium" :aria-label="t('case.tickets.protocolList')" data-testid="protocols">
          <li v-for="item in protocols" :key="item.evidenceId" :data-testid="`protocol-${item.version}`">
            {{ t('case.tickets.version', { version: item.version }) }}
            <StatusPill v-if="item.current" :label="t('case.tickets.current')" tone="primary" />
            <span class="text-on-surface-variant">
              · {{ t('case.detail.entries', { count: item.entryCount }) }} ·
              <time :datetime="new Date(item.createdAt).toISOString()">{{ formatDateTime(item.createdAt, locale) }}</time>
            </span>
          </li>
        </ul>
      </template>
      <MdButton variant="text" class="self-start" data-testid="protocols-goto" @click="goToProtocols">
        {{ t('case.tickets.openProtocols') }}
      </MdButton>
    </div>
  </section>
</template>
