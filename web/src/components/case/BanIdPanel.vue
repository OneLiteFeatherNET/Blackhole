<script setup lang="ts">
// The Ban ID of the punishment: masked until a moderator asks. The revealed value is a credential,
// so it lives in this component's memory only: not in Pinia, not in browser storage, not in the URL
// or the router state, never logged. It is dropped when the case changes, after a leak or reissue,
// and when the page is left.
import { onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { BanIdLeakReason, BanIdReissueReason, Case } from '../../api/types'
import { useSessionStore } from '../../stores/session'
import MdButton from '../md/MdButton.vue'
import StatusPill from '../StatusPill.vue'
import ReasonDialog from './ReasonDialog.vue'

const LEAK_REASONS: BanIdLeakReason[] = ['POSTED_PUBLIC', 'SHARED', 'FOUND', 'OTHER']
const REISSUE_REASONS: BanIdReissueReason[] = ['NOT_PLAYERS_FAULT', 'OTHER']
const MASK = '••••••••'

const props = defineProps<{ kase: Case }>()
const emit = defineEmits<{ changed: [message: string] }>()

const { t, te } = useI18n()
const api = useApi()
const session = useSessionStore()

const banId = ref<string | null>(null)
const revealing = ref(false)
const error = ref('')
const announcement = ref('')
const leaking = ref(false)
const reissuing = ref(false)

function hide(): void {
  banId.value = null
  error.value = ''
}

async function toggle(): Promise<void> {
  if (banId.value !== null) {
    hide()
    announcement.value = t('case.banId.hidden')
    return
  }
  if (revealing.value) return
  revealing.value = true
  error.value = ''
  const requested = props.kase.punishmentIdentifier
  const result = await api.revealBanId(requested)
  revealing.value = false
  // The page may have moved to another case while the request ran.
  if (requested !== props.kase.punishmentIdentifier) return
  if (!result.ok) {
    error.value = result.error.status === 404 ? t('case.banId.notIssued') : result.error.message
    return
  }
  banId.value = result.data
  announcement.value = t('case.banId.shown')
}

watch(() => props.kase.punishmentIdentifier, hide)
onBeforeUnmount(hide)

function done(message: string): void {
  hide()
  leaking.value = false
  reissuing.value = false
  emit('changed', message)
}

const leakReason = (): string => {
  const reason = props.kase.banIdLeakReason
  return reason && te(`case.banId.leakReason.${reason}`) ? t(`case.banId.leakReason.${reason}`) : (reason ?? '')
}
</script>

<template>
  <section :aria-label="t('case.banId.title')" class="flex flex-col gap-3" data-testid="ban-id-panel">
    <h3 class="text-title-medium">{{ t('case.banId.title') }}</h3>

    <p class="sr-only" role="status" aria-live="polite" data-testid="ban-id-status">{{ announcement }}</p>

    <div class="flex flex-wrap items-center gap-3">
      <code class="rounded-md-xs bg-surface-container-highest px-3 py-2 text-title-medium tracking-widest" data-testid="ban-id-value" :data-revealed="banId !== null">
        <span v-if="banId !== null">{{ banId }}</span>
        <span v-else aria-hidden="true">{{ MASK }}</span>
        <span v-if="banId === null" class="sr-only">{{ t('case.banId.masked') }}</span>
      </code>
      <MdButton v-if="session.moderator" variant="outlined" :disabled="revealing" :aria-pressed="banId !== null" data-testid="ban-id-toggle" @click="toggle">
        {{ banId !== null ? t('case.banId.hide') : t('case.banId.reveal') }}
      </MdButton>
      <StatusPill v-if="kase.banIdLeakedAt != null" :label="t('case.banId.leaked', { reason: leakReason() })" tone="tertiary" data-testid="ban-id-leaked" />
    </div>
    <p v-if="error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container" data-testid="ban-id-error">{{ error }}</p>
    <p class="text-body-small text-on-surface-variant">{{ t('case.banId.hint') }}</p>

    <div v-if="session.moderator" class="flex flex-wrap gap-2">
      <MdButton variant="outlined" :disabled="kase.banIdLeakedAt != null" data-testid="ban-id-leak" @click="leaking = true">{{ t('case.banId.markLeaked') }}</MdButton>
      <MdButton v-if="session.administrator" variant="outlined" data-testid="ban-id-reissue" @click="reissuing = true">{{ t('case.banId.reissue') }}</MdButton>
    </div>
    <p v-else class="text-body-medium text-on-surface-variant" data-testid="ban-id-read-only">{{ t('case.banId.readOnly') }}</p>

    <ReasonDialog
      :open="leaking"
      name="leak"
      :headline="t('case.banId.leakTitle')"
      :body="t('case.banId.leakBody')"
      :confirm-label="t('case.banId.leakConfirm')"
      :reasons="LEAK_REASONS"
      reason-prefix="case.banId.leakReason"
      :run="(reason: string) => api.markBanIdLeaked(kase.punishmentIdentifier, reason as BanIdLeakReason)"
      @close="leaking = false"
      @done="done(t('case.banId.leakDone'))"
    />
    <ReasonDialog
      :open="reissuing"
      name="reissue"
      :headline="t('case.banId.reissueTitle')"
      :body="t('case.banId.reissueBody')"
      :confirm-label="t('case.banId.reissueConfirm')"
      :reasons="REISSUE_REASONS"
      reason-prefix="case.banId.reissueReason"
      :run="(reason: string) => api.reissueBanId(kase.punishmentIdentifier, reason as BanIdReissueReason)"
      @close="reissuing = false"
      @done="done(t('case.banId.reissueDone'))"
    />
  </section>
</template>
