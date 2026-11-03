<script setup lang="ts">
// Changes the login policy of one class. The change is confirmed with the previous value named; nothing is saved before that.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { IntelClass, IntelPolicy } from '../../api/types'
import MdButton from '../md/MdButton.vue'
import MdDialog from '../md/MdDialog.vue'
import MdSelect from '../md/MdSelect.vue'
import { choosablePolicies } from './policyRows'

const props = defineProps<{ open: boolean; classification: IntelClass; current: IntelPolicy; administrator: boolean }>()
const emit = defineEmits<{ close: []; saved: [message: string] }>()

const { t } = useI18n()
const api = useApi()

const draft = ref<IntelPolicy>(props.current)
const confirming = ref(false)
const saving = ref(false)
const error = ref('')

watch(
  () => props.open,
  (open) => {
    if (!open) return
    draft.value = props.current
    confirming.value = false
    error.value = ''
  },
)

const policyOptions = computed(() => choosablePolicies(props.administrator).map((value) => ({ value, label: t(`intel.policies.${value}`) })))
const changed = computed(() => draft.value !== props.current)
const confirmation = computed(() =>
  t('intel.policy.confirmBody', {
    classification: t(`intel.classes.${props.classification}`),
    previous: t(`intel.policies.${props.current}`),
    next: t(`intel.policies.${draft.value}`),
  }),
)

async function confirm(): Promise<void> {
  if (saving.value) return
  saving.value = true
  error.value = ''
  const next = draft.value
  const result = await api.setIntelPolicy(props.classification, next)
  saving.value = false
  if (!result.ok) {
    error.value = result.error.message
    return
  }
  confirming.value = false
  emit('saved', t('intel.policy.saved', { classification: t(`intel.classes.${props.classification}`), policy: t(`intel.policies.${next}`) }))
}
</script>

<template>
  <MdDialog :open="open" :headline="t('intel.policy.editTitle', { classification: t(`intel.classes.${classification}`) })" @close="emit('close')">
    <div class="flex flex-col gap-4 pt-2">
      <MdSelect v-model="draft" data-testid="policy-select" :label="t('intel.policy.policyLabel')" :options="policyOptions" :disabled="saving" />
      <p v-if="error" data-testid="policy-error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">{{ error }}</p>
    </div>
    <template #actions>
      <MdButton variant="text" data-testid="policy-cancel" @click="emit('close')">{{ t('actions.cancel') }}</MdButton>
      <MdButton data-testid="policy-save" :disabled="!changed || saving" @click="confirming = true">{{ t('intel.policy.save') }}</MdButton>
    </template>

    <MdDialog :open="confirming" :headline="t('intel.policy.confirmTitle')" @close="confirming = false">
      <p data-testid="policy-confirmation">{{ confirmation }}</p>
      <template #actions>
        <MdButton variant="text" data-testid="policy-confirm-cancel" @click="confirming = false">{{ t('actions.cancel') }}</MdButton>
        <MdButton data-testid="policy-confirm" :disabled="saving" @click="confirm">{{ t('intel.policy.confirm') }}</MdButton>
      </template>
    </MdDialog>
  </MdDialog>
</template>
