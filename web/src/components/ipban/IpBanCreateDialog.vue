<script setup lang="ts">
// Holds one address or CIDR range. A moderator's ban must expire within the server's cap and has no
// permanent option; an administrator may tick permanent. The form checks what it can, the API has the last word.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import { useSessionStore } from '../../stores/session'
import MdButton from '../md/MdButton.vue'
import MdCheckbox from '../md/MdCheckbox.vue'
import MdDialog from '../md/MdDialog.vue'
import MdDurationField from '../md/MdDurationField.vue'
import MdTextField from '../md/MdTextField.vue'
import { REASON_MAX, expiresAtFor, ipBanErrorKey, validateIpBanForm } from './ipBanRules'

defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: []; done: [message: string] }>()

const { t } = useI18n()
const api = useApi()
const session = useSessionStore()

const address = ref('')
const reason = ref('')
const duration = ref('')
const permanent = ref(false)
const saving = ref(false)
const error = ref('')
/** Set once anything is typed: the messages for the fields show from then on, a fresh form stays quiet. */
const dirty = ref(false)

const errors = computed(() =>
  validateIpBanForm({ address: address.value, reason: reason.value, duration: duration.value, permanent: permanent.value }, { administrator: session.administrator }),
)
const canSubmit = computed(() => Object.keys(errors.value).length === 0 && !saving.value)

const addressError = computed(() => (dirty.value && errors.value.address ? t(`ipBans.validation.address.${errors.value.address}`) : ''))
const reasonError = computed(() => (dirty.value && errors.value.reason ? t(`ipBans.validation.reason.${errors.value.reason}`) : ''))
const durationError = computed(() => (dirty.value && errors.value.duration === 'tooLong' ? t('ipBans.validation.duration.tooLong') : ''))

watch([address, reason, duration, permanent], () => {
  dirty.value = true
})

function reset(): void {
  address.value = ''
  reason.value = ''
  duration.value = ''
  permanent.value = false
  error.value = ''
  dirty.value = false
}

async function submit(): Promise<void> {
  dirty.value = true
  if (!canSubmit.value) return
  saving.value = true
  error.value = ''
  const result = await api.createIpBan({
    address: address.value,
    reason: reason.value,
    expiresAt: expiresAtFor({ duration: duration.value, permanent: permanent.value }, Date.now()),
  })
  saving.value = false
  if (!result.ok) {
    const key = ipBanErrorKey(result.error, 'create')
    error.value = key ? t(`ipBans.errors.${key}`) : result.error.message
    return
  }
  const warnings = result.data.warnings
  const message = warnings.length > 0 ? t('ipBans.created.withWarnings', { warnings: warnings.join('; ') }) : t('ipBans.created.done')
  reset()
  emit('done', message)
}

function close(): void {
  reset()
  emit('close')
}
</script>

<template>
  <MdDialog :open="open" :headline="t('ipBans.create.title')" @close="close">
    <div class="flex flex-col gap-4 pt-2">
      <MdTextField
        v-model="address"
        data-testid="address"
        required
        :label="t('ipBans.create.address')"
        :supporting-text="t('ipBans.create.addressHint')"
        :error="addressError"
      />
      <MdTextField v-model="reason" data-testid="reason" required :label="t('ipBans.create.reason')" :supporting-text="t('ipBans.create.reasonHint', { max: REASON_MAX })" :error="reasonError" />
      <MdDurationField
        v-model="duration"
        data-testid="duration"
        :label="t('ipBans.create.duration')"
        :required="!permanent"
        :disabled="permanent"
        :chips="['1d', '7d', '30d']"
        :supporting-text="session.administrator ? undefined : t('ipBans.create.moderatorCap')"
        :error="durationError"
      />
      <MdCheckbox v-if="session.administrator" v-model="permanent" data-testid="permanent" :label="t('ipBans.create.permanent')" :supporting-text="t('ipBans.create.permanentHint')" />
      <p v-if="error" role="alert" data-testid="error" class="rounded-md-md bg-error-container p-3 text-on-error-container">{{ error }}</p>
    </div>
    <template #actions>
      <MdButton variant="text" data-testid="cancel" @click="close">{{ t('actions.cancel') }}</MdButton>
      <MdButton data-testid="submit" :disabled="!canSubmit" @click="submit">{{ t('ipBans.create.submit') }}</MdButton>
    </template>
  </MdDialog>
</template>
