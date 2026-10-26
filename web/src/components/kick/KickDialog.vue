<script setup lang="ts">
// Kicks one player off the network, a server or a section (design section 7 of add-kick). The answer of POST /kick is
// immediate; the proxies report afterwards, so the dialog reads GET /kick/{id} every second and shows the outcome as a
// chip. A retry after a failed request keeps the Idempotency-Key, so it can never kick twice.
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { CloudNetCatalogue, KickResult, KickScope, Section } from '../../api/types'
import MdButton from '../md/MdButton.vue'
import MdChip from '../md/MdChip.vue'
import MdDialog from '../md/MdDialog.vue'
import MdProgress from '../md/MdProgress.vue'
import MdSelect from '../md/MdSelect.vue'
import MdTextField from '../md/MdTextField.vue'
import KickStatusChip from './KickStatusChip.vue'
import { kickReasonProblem, MAX_KICK_REASON } from './kickReason'
import { pollKick, realKickTimer, type KickTimer } from './KickStatusPoller'
import { newIdempotencyKey, targetsFor } from './kickTargets'

const props = withDefaults(defineProps<{ open: boolean; owner: string; name?: string; timer?: KickTimer }>(), {
  name: '',
  timer: () => realKickTimer,
})
const emit = defineEmits<{ close: []; done: [message: string] }>()

const SCOPES: KickScope[] = ['NETWORK', 'SERVER', 'SECTION']

const { t } = useI18n()
const api = useApi()

const scope = ref<KickScope>('NETWORK')
const target = ref('')
const reason = ref('')
const submitted = ref(false)
const sending = ref(false)
const waiting = ref(false)
const error = ref('')
const warnings = ref<string[]>([])
const outcome = ref<KickResult | null>(null)
const timedOut = ref(false)
const sections = ref<Section[]>([])
const catalogue = ref<CloudNetCatalogue | null>(null)

let key: string | null = null
let stopPolling: () => void = () => {}

const targets = computed(() => targetsFor(scope.value, sections.value, catalogue.value))
const reasonProblem = computed(() => kickReasonProblem(reason.value))
const reasonError = computed(() => (submitted.value && reasonProblem.value ? t(`kick.reason.${reasonProblem.value}`) : ''))
const targetError = computed(() => (submitted.value && scope.value !== 'NETWORK' && target.value === '' ? t('kick.targetRequired') : ''))
const locked = computed(() => sending.value || waiting.value || outcome.value !== null || timedOut.value)

function reset(): void {
  stopPolling()
  scope.value = 'NETWORK'
  target.value = ''
  reason.value = ''
  submitted.value = false
  sending.value = false
  waiting.value = false
  error.value = ''
  warnings.value = []
  outcome.value = null
  timedOut.value = false
  key = null
}

watch(
  () => props.open,
  (open) => {
    if (open) reset()
    else stopPolling()
  },
  { immediate: true },
)
onBeforeUnmount(() => stopPolling())

// The target lists are only needed once a scope asks for them.
let loaded = false
watch(scope, async (next) => {
  target.value = ''
  if (next === 'NETWORK' || loaded) return
  loaded = true
  const [list, services] = await Promise.all([api.sections(), api.cloudnetCatalogue()])
  if (list.ok) sections.value = list.data
  if (services.ok) catalogue.value = services.data
})

async function submit(): Promise<void> {
  submitted.value = true
  if (locked.value || reasonProblem.value || (scope.value !== 'NETWORK' && target.value === '')) return
  sending.value = true
  error.value = ''
  key ??= newIdempotencyKey()
  const result = await api.kickPlayer(
    { owner: props.owner, scope: scope.value, target: scope.value === 'NETWORK' ? null : target.value, reason: reason.value.trim() },
    key,
  )
  sending.value = false
  if (!result.ok) {
    error.value = result.error.message
    return
  }
  warnings.value = result.data.warnings
  waiting.value = true
  const id = result.data.id
  stopPolling = pollKick({
    fetch: () => api.kick(id),
    timer: props.timer,
    now: () => Date.now(),
    deadlineAt: result.data.deadlineAt,
    onDone(done) {
      waiting.value = false
      if (done.kind === 'settled') {
        outcome.value = done.kick
        emit('done', t(`kick.status.${done.kick.status}`))
      } else if (done.kind === 'timeout') timedOut.value = true
      else error.value = done.message
    },
  })
}
</script>

<template>
  <MdDialog :open="open" :headline="t('kick.title', { name: name || owner.slice(0, 8) })" @close="emit('close')">
    <form class="flex flex-col gap-4" novalidate @submit.prevent="submit">
      <p>{{ t('kick.intro') }}</p>

      <div role="group" :aria-label="t('kick.scope.label')" class="flex flex-wrap gap-2" data-testid="kick-scope">
        <MdChip
          v-for="value in SCOPES"
          :key="value"
          :label="t(`kick.scope.${value}`)"
          :selected="scope === value"
          :data-testid="`scope-${value}`"
          @click="!locked && (scope = value)"
        />
      </div>

      <MdSelect
        v-if="scope !== 'NETWORK'"
        v-model="target"
        :label="t(`kick.target.${scope}`)"
        :placeholder="t('kick.target.choose')"
        :options="targets"
        :error="targetError"
        :disabled="locked"
        data-testid="kick-target"
      />

      <MdTextField
        v-model="reason"
        :label="t('kick.reason.label')"
        :error="reasonError"
        :supporting-text="t('kick.reason.hint')"
        :disabled="locked"
        multiline
        :rows="2"
        data-testid="kick-reason"
      />
      <p class="-mt-3 text-right text-body-small text-on-surface-variant">{{ reason.trim().length }} / {{ MAX_KICK_REASON }}</p>

      <MdProgress v-if="sending || waiting" :label="sending ? t('kick.sending') : t('kick.waiting')" />
      <ul v-if="warnings.length > 0" class="list-disc rounded-md-md bg-tertiary-container p-3 pl-7 text-on-tertiary-container" data-testid="kick-warnings">
        <li v-for="warning in warnings" :key="warning">{{ warning }}</li>
      </ul>
      <div v-if="outcome" data-testid="kick-outcome"><KickStatusChip :status="outcome.status" /></div>
      <p v-if="timedOut" role="status" data-testid="kick-timeout">{{ t('kick.timedOut') }}</p>
      <p v-if="error" role="alert" data-testid="error" class="rounded-md-md bg-error-container p-3 text-on-error-container">{{ error }}</p>
    </form>
    <template #actions>
      <MdButton variant="text" data-testid="cancel" @click="emit('close')">{{ outcome || timedOut ? t('kick.close') : t('actions.cancel') }}</MdButton>
      <MdButton v-if="!outcome && !timedOut" variant="error" data-testid="confirm" :disabled="locked" @click="submit">{{ t('kick.submit') }}</MdButton>
    </template>
  </MdDialog>
</template>
