<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { AreaBanKind } from '../../api/types'
import { useSessionStore } from '../../stores/session'
import MdButton from '../md/MdButton.vue'
import MdDialog from '../md/MdDialog.vue'
import MdSelect from '../md/MdSelect.vue'
import MdTextField from '../md/MdTextField.vue'
import { durationChoices, durationSecondsOf, kindChoices, PERMANENT, REASON_MAX_LENGTH, reasonIsValid, valueIsValid } from './areaBanRules'

// Creates an ASN or country ban. The options a role may not use are not offered at all; the server refuses them anyway.
const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: []; created: [] }>()

const { t } = useI18n()
const api = useApi()
const session = useSessionStore()

const kind = ref<AreaBanKind>('ASN')
const value = ref('')
const reason = ref('')
const duration = ref('')
const error = ref('')
const saving = ref(false)

const kindOptions = computed(() => kindChoices(session.administrator).map((choice) => ({ value: choice, label: t(`areaBans.kind.${choice}`) })))

const durationOptions = computed(() =>
  durationChoices(session.administrator).map((choice) =>
    choice === PERMANENT
      ? { value: choice, label: t('areaBans.duration.permanent') }
      : { value: choice, label: t('areaBans.duration.days', { n: Number(choice) / 86400 }, Number(choice) / 86400) },
  ),
)

const valueLabel = computed(() => (kind.value === 'ASN' ? t('areaBans.value.asn') : t('areaBans.value.country')))
const valueSupport = computed(() => (kind.value === 'ASN' ? t('areaBans.value.asnHint') : t('areaBans.value.countryHint')))
const canSubmit = computed(() => valueIsValid(kind.value, value.value) && reasonIsValid(reason.value) && !saving.value)

watch(
  () => props.open,
  (open) => {
    if (!open) return
    kind.value = 'ASN'
    value.value = ''
    reason.value = ''
    duration.value = durationChoices(session.administrator)[0]!
    error.value = ''
  },
  { immediate: true },
)

async function submit(): Promise<void> {
  if (!canSubmit.value) return
  saving.value = true
  error.value = ''
  const result = await api.createAreaBan({
    kind: kind.value,
    value: value.value,
    reason: reason.value,
    durationSeconds: durationSecondsOf(duration.value),
  })
  saving.value = false
  if (!result.ok) {
    error.value = result.error.message
    return
  }
  emit('created')
}
</script>

<template>
  <MdDialog :open="open" :headline="t('areaBans.create.title')" @close="emit('close')">
    <div class="flex flex-col gap-4">
      <p class="text-body-medium text-on-surface-variant">{{ t('areaBans.create.intro') }}</p>
      <MdSelect
        v-model="kind"
        data-testid="area-kind"
        required
        :label="t('areaBans.columns.kind')"
        :options="kindOptions"
      />
      <MdTextField
        v-model="value"
        data-testid="area-value"
        required
        :label="valueLabel"
        :supporting-text="valueSupport"
      />
      <MdTextField
        v-model="reason"
        data-testid="area-reason"
        required
        :label="t('areaBans.columns.reason')"
        :supporting-text="t('areaBans.reasonHint', { max: REASON_MAX_LENGTH })"
      />
      <MdSelect
        v-model="duration"
        data-testid="area-duration"
        required
        :label="t('areaBans.duration.label')"
        :supporting-text="session.administrator ? undefined : t('areaBans.moderatorLimit')"
        :options="durationOptions"
      />
      <p v-if="error" data-testid="error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">
        {{ error }}
      </p>
    </div>
    <template #actions>
      <MdButton variant="text" data-testid="cancel" @click="emit('close')">{{ t('actions.cancel') }}</MdButton>
      <MdButton data-testid="confirm" :disabled="!canSubmit" @click="submit">{{ t('areaBans.create.confirm') }}</MdButton>
    </template>
  </MdDialog>
</template>
