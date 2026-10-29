<script setup lang="ts">
// The login policy per class. Only an administrator sees the change control, and DENY is offered to an administrator only.
// A change is confirmed with the previous value named; nothing is saved before that.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { IntelClass, IntelPolicy, IntelPolicyEntry } from '../../api/types'
import MdButton from '../md/MdButton.vue'
import MdDialog from '../md/MdDialog.vue'
import MdSelect from '../md/MdSelect.vue'
import StatusPill from '../StatusPill.vue'

const props = defineProps<{ policies: IntelPolicyEntry[]; administrator: boolean }>()
const emit = defineEmits<{ saved: [message: string] }>()

const { t } = useI18n()
const api = useApi()

const CLASSES: IntelClass[] = ['VPN', 'HOSTING', 'PROXY']
const tones = { ALLOW: 'primary', FLAG: 'tertiary', DENY: 'error' } as const

// A class without a stored row is FLAG, as the backend reports it.
const rows = computed(() =>
  CLASSES.map((classification) => ({
    classification,
    current: (props.policies.find((entry) => entry.classification === classification)?.policy ?? 'FLAG') as IntelPolicy,
  })),
)
const policyOptions = computed(() => {
  const values: IntelPolicy[] = props.administrator ? ['ALLOW', 'FLAG', 'DENY'] : ['ALLOW', 'FLAG']
  return values.map((value) => ({ value, label: t(`intel.policies.${value}`) }))
})

const drafts = ref<Partial<Record<IntelClass, IntelPolicy>>>({})
const pending = ref<IntelClass | null>(null)
const saving = ref(false)
const error = ref('')

const draftOf = (row: { classification: IntelClass; current: IntelPolicy }) => drafts.value[row.classification] ?? row.current
const changed = (row: { classification: IntelClass; current: IntelPolicy }) => draftOf(row) !== row.current

const pendingRow = computed(() => rows.value.find((row) => row.classification === pending.value) ?? null)
const confirmation = computed(() => {
  const row = pendingRow.value
  if (!row) return ''
  return t('intel.policy.confirmBody', {
    classification: t(`intel.classes.${row.classification}`),
    previous: t(`intel.policies.${row.current}`),
    next: t(`intel.policies.${draftOf(row)}`),
  })
})

function ask(classification: IntelClass): void {
  error.value = ''
  pending.value = classification
}

function close(): void {
  pending.value = null
  error.value = ''
}

async function confirm(): Promise<void> {
  const row = pendingRow.value
  if (!row || saving.value) return
  const next = draftOf(row)
  saving.value = true
  error.value = ''
  const result = await api.setIntelPolicy(row.classification, next)
  saving.value = false
  if (!result.ok) {
    error.value = result.error.message
    return
  }
  delete drafts.value[row.classification]
  pending.value = null
  emit('saved', t('intel.policy.saved', { classification: t(`intel.classes.${row.classification}`), policy: t(`intel.policies.${next}`) }))
}
</script>

<template>
  <div class="flex flex-col gap-3">
    <section
      v-for="row in rows"
      :key="row.classification"
      :data-testid="`policy-${row.classification}`"
      class="flex flex-col gap-3 rounded-md-lg border border-outline-variant p-4 sm:flex-row sm:items-end"
    >
      <div class="min-w-0 flex-1">
        <h3 class="text-title-medium">{{ t(`intel.classes.${row.classification}`) }}</h3>
        <p class="text-body-medium text-on-surface-variant">{{ t(`intel.classHint.${row.classification}`) }}</p>
      </div>
      <StatusPill :label="t(`intel.policies.${row.current}`)" :tone="tones[row.current]" />
      <template v-if="administrator">
        <div class="flex flex-wrap items-end gap-2">
          <MdSelect
            :model-value="draftOf(row)"
            :label="t('intel.policy.policyLabel')"
            :options="policyOptions"
            :disabled="saving"
            @update:model-value="(value: string) => (drafts[row.classification] = value as IntelPolicy)"
          />
          <MdButton data-testid="policy-save" :disabled="!changed(row) || saving" @click="ask(row.classification)">
            {{ t('intel.policy.save') }}
          </MdButton>
        </div>
      </template>
    </section>

    <MdDialog :open="pending !== null" :headline="t('intel.policy.confirmTitle')" @close="close">
      <div class="flex flex-col gap-3">
        <p data-testid="policy-confirmation">{{ confirmation }}</p>
        <p v-if="error" data-testid="policy-error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">
          {{ error }}
        </p>
      </div>
      <template #actions>
        <MdButton variant="text" data-testid="policy-cancel" @click="close">{{ t('actions.cancel') }}</MdButton>
        <MdButton data-testid="policy-confirm" :disabled="saving" @click="confirm">{{ t('intel.policy.confirm') }}</MdButton>
      </template>
    </MdDialog>
  </div>
</template>
