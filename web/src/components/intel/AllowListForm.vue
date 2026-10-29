<script setup lang="ts">
// Exempts a player from the VPN and hosting policy. The reason is required. A moderator may choose 1 to 90 days; only an
// administrator may choose a permanent exemption. The backend stays authoritative; this only keeps the form honest.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { AllowListEntry } from '../../api/types'
import { formatDateTime, shortHash } from '../../format'
import MdButton from '../md/MdButton.vue'
import MdSelect from '../md/MdSelect.vue'
import MdTextField from '../md/MdTextField.vue'
import StatusPill from '../StatusPill.vue'

const props = defineProps<{
  administrator: boolean
  /** The exemptions added in this view; a stored exemption past its expiry is shown as expired. */
  entries: AllowListEntry[]
  /** Epoch milliseconds to judge expiry by; the current time when omitted. */
  now?: number
}>()
const emit = defineEmits<{ added: [entry: AllowListEntry] }>()

const MAX_REASON = 200
const PERMANENT = 'permanent'

const { t, locale } = useI18n()
const api = useApi()

const player = ref('')
const reason = ref('')
const duration = ref('7')
const touched = ref(false)
const submitting = ref(false)
const error = ref('')

const durationOptions = computed(() => {
  const days = ['1', '7', '30', '90'].map((value) => ({ value, label: t('intel.allowList.days', { days: value }) }))
  return props.administrator ? [...days, { value: PERMANENT, label: t('intel.allowList.permanent') }] : days
})

const reasonMissing = computed(() => reason.value.trim() === '')
const reasonTooLong = computed(() => reason.value.trim().length > MAX_REASON)
const reasonError = computed(() => {
  if (reasonTooLong.value) return t('intel.allowList.reasonTooLong')
  return touched.value && reasonMissing.value ? t('intel.allowList.reasonRequired') : ''
})
const valid = computed(() => player.value.trim() !== '' && !reasonMissing.value && !reasonTooLong.value)

const nowMs = () => props.now ?? Date.now()
const isExpired = (entry: AllowListEntry) => entry.expiresAt !== null && entry.expiresAt !== undefined && entry.expiresAt <= nowMs()

function expiryLabel(entry: AllowListEntry): string {
  if (entry.expiresAt === null || entry.expiresAt === undefined) return t('intel.allowList.permanentLabel')
  if (isExpired(entry)) return t('intel.allowList.expired')
  return t('intel.allowList.until', { when: formatDateTime(entry.expiresAt, locale.value) })
}

async function submit(): Promise<void> {
  if (submitting.value || !valid.value) return
  submitting.value = true
  error.value = ''
  const resolved = await api.resolvePlayer(player.value.trim())
  if (!resolved.ok) {
    submitting.value = false
    error.value = resolved.error.message
    return
  }
  const result = await api.addIntelAllowListEntry({
    owner: resolved.data.owner,
    reason: reason.value.trim(),
    durationDays: duration.value === PERMANENT ? null : Number(duration.value),
  })
  submitting.value = false
  if (!result.ok) {
    error.value = result.error.message
    return
  }
  emit('added', result.data)
  player.value = ''
  reason.value = ''
  touched.value = false
}
</script>

<template>
  <div class="flex flex-col gap-6">
    <form class="flex flex-col gap-4" data-testid="allow-list-form" @submit.prevent="submit">
      <MdTextField
        v-model="player"
        :label="t('intel.allowList.player')"
        :disabled="submitting"
        :supporting-text="t('intel.allowList.playerHint')"
      />
      <MdTextField
        v-model="reason"
        :label="t('intel.allowList.reason')"
        @update:model-value="touched = true"
        :disabled="submitting"
        :error="reasonError"
        :supporting-text="`${reason.trim().length} / ${MAX_REASON}`"
      />
      <MdSelect v-model="duration" :label="t('intel.allowList.duration')" :options="durationOptions" :disabled="submitting" />
      <p v-if="error" role="alert" data-testid="allow-list-error" class="rounded-md-md bg-error-container p-3 text-on-error-container">
        {{ error }}
      </p>
      <div>
        <MdButton type="submit" data-testid="allow-list-submit" :disabled="!valid || submitting">{{ t('intel.allowList.submit') }}</MdButton>
      </div>
    </form>

    <section v-if="entries.length" :aria-label="t('intel.allowList.added')">
      <h2 class="mb-2 text-title-medium">{{ t('intel.allowList.added') }}</h2>
      <ul class="flex flex-col gap-2">
        <li
          v-for="(entry, index) in entries"
          :key="`${entry.owner}-${entry.createdAt}-${index}`"
          data-testid="allow-list-entry"
          class="flex flex-wrap items-center gap-3 rounded-md-md border border-outline-variant p-3"
        >
          <span class="font-mono text-body-medium" :title="entry.owner">{{ shortHash(entry.owner) }}</span>
          <span class="min-w-0 flex-1 break-words text-body-medium">{{ entry.reason }}</span>
          <StatusPill :label="expiryLabel(entry)" :tone="isExpired(entry) ? 'error' : 'neutral'" />
        </li>
      </ul>
    </section>
  </div>
</template>
