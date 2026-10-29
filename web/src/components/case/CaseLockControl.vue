<script setup lang="ts">
// The staff lock of the appeal ticket, next to the ticket states: who claimed the case, whether the
// appeal is decided and whether the channel is locked. Moderators and admins lock an open ticket and
// unlock a lock staff set; a leak freeze is shown but never lifted here. The bot is the only reader of
// the ticket, so the page starts from what the case shows (the leak banner) and learns the rest from
// its own changes: the server answers the state after each one.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import { useSessionStore } from '../../stores/session'
import MdButton from '../md/MdButton.vue'
import { lockSource, type LockState, type TicketView } from './tickets'

const props = defineProps<{ reference: string; tickets: TicketView[]; assigneeName: string | null; leakFrozen: boolean; known: LockState | null }>()
const emit = defineEmits<{ changed: [message: string] }>()

const { t } = useI18n()
const api = useApi()
const session = useSessionStore()

/** The newest ticket is the one staff works on. */
const ticket = computed(() => props.tickets[props.tickets.length - 1] ?? null)
const decided = computed(() => ticket.value !== null && ticket.value.phase !== 'OPEN')

/** The answer to this page's own last change; the state the case knows (`known`) applies again once that moves on. */
const lock = ref<LockState | null>(null)
const saving = ref(false)
const error = ref('')

const current = computed<LockState | null>(() => (props.leakFrozen ? { frozen: true, source: 'LEAK' } : (lock.value ?? props.known)))
const locked = computed(() => current.value?.frozen === true)
const leakLocked = computed(() => locked.value && current.value?.source === 'LEAK')
const canLock = computed(() => session.moderator && ticket.value?.phase === 'OPEN' && !locked.value)
const canUnlock = computed(() => session.moderator && locked.value && !leakLocked.value)

const lockText = computed(() => {
  if (locked.value) return leakLocked.value ? t('case.lock.lockedLeak') : t('case.lock.lockedStaff')
  return current.value === null ? t('case.lock.unknown') : t('case.lock.unlocked')
})

watch(
  () => props.reference,
  () => {
    lock.value = null
    error.value = ''
  },
)

// A newer state from the case or the timeline supersedes this page's last answer.
watch(
  () => props.known,
  () => {
    lock.value = null
  },
)

async function change(frozen: boolean): Promise<void> {
  if (saving.value) return
  saving.value = true
  error.value = ''
  const requested = props.reference
  const result = await api.setStaffTicketFreeze(requested, frozen)
  saving.value = false
  // The page may have moved to another case while the request ran.
  if (requested !== props.reference) return
  if (!result.ok) {
    error.value = result.error.message
    return
  }
  const source = result.data.freezeSource
  lock.value = { frozen: result.data.frozen, source: lockSource(source) }
  emit('changed', t(frozen ? 'case.lock.lockedDone' : 'case.lock.unlockedDone'))
}
</script>

<template>
  <section v-if="ticket" :aria-label="t('case.lock.title')" class="flex flex-col gap-2 rounded-md-md border border-outline-variant bg-surface p-4" data-testid="lock-control">
    <h4 class="text-title-small">{{ t('case.lock.title') }}</h4>

    <dl class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-body-medium">
      <dt class="text-on-surface-variant">{{ t('case.lock.claimedBy') }}</dt>
      <dd data-testid="lock-assignee">{{ assigneeName ?? t('case.lock.notClaimed') }}</dd>
      <dt class="text-on-surface-variant">{{ t('case.lock.appeal') }}</dt>
      <dd data-testid="lock-decided">{{ decided ? t('case.lock.decided') : t('case.lock.notDecided') }}</dd>
      <dt class="text-on-surface-variant">{{ t('case.lock.state') }}</dt>
      <dd data-testid="lock-state">{{ lockText }}</dd>
    </dl>

    <p v-if="leakLocked" role="status" class="rounded-md-md bg-error-container p-3 text-on-error-container" data-testid="lock-leak">
      {{ t('case.lock.leak') }}
    </p>
    <p v-if="error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container" data-testid="lock-error">{{ error }}</p>

    <div v-if="canLock || canUnlock" class="flex flex-wrap gap-2">
      <MdButton v-if="canLock" variant="outlined" :disabled="saving" data-testid="lock-ticket" @click="change(true)">
        {{ t('case.lock.lock') }}
      </MdButton>
      <MdButton v-if="canUnlock" variant="outlined" :disabled="saving" data-testid="unlock-ticket" @click="change(false)">
        {{ t('case.lock.unlock') }}
      </MdButton>
    </div>
    <p class="text-body-small text-on-surface-variant">{{ t('case.lock.hint') }}</p>
  </section>
</template>
