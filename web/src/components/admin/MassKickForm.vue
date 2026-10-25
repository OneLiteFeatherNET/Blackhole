<script setup lang="ts">
// Kicks everybody from a server or a section: one audited admin action, no punishment, case or ELO. It is rare, so it
// is a form on the page and not a dialog. The confirmation is typing the target name back exactly (no second approval).
// ADMIN only: the form renders nothing for any other role, and the backend enforces the role anyway.
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { CloudNetCatalogue, KickResult, KickScope, Section } from '../../api/types'
import { useSessionStore } from '../../stores/session'
import MdButton from '../md/MdButton.vue'
import MdChip from '../md/MdChip.vue'
import MdProgress from '../md/MdProgress.vue'
import MdSelect from '../md/MdSelect.vue'
import MdTextField from '../md/MdTextField.vue'
import KickStatusChip from '../kick/KickStatusChip.vue'
import { kickReasonProblem } from '../kick/kickReason'
import { pollKick, realKickTimer, type KickTimer } from '../kick/KickStatusPoller'
import { newIdempotencyKey, targetsFor } from '../kick/kickTargets'

const props = withDefaults(defineProps<{ timer?: KickTimer }>(), { timer: () => realKickTimer })

const SCOPES: Exclude<KickScope, 'NETWORK'>[] = ['SERVER', 'SECTION']

const { t } = useI18n()
const api = useApi()
const session = useSessionStore()

const scope = ref<Exclude<KickScope, 'NETWORK'>>('SERVER')
const target = ref('')
const reason = ref('')
const typed = ref('')
const submitted = ref(false)
const sending = ref(false)
const waiting = ref(false)
const error = ref('')
const outcome = ref<KickResult | null>(null)
const timedOut = ref(false)
const sections = ref<Section[]>([])
const catalogue = ref<CloudNetCatalogue | null>(null)

let key: string | null = null
let stopPolling: () => void = () => {}

const targets = computed(() => targetsFor(scope.value, sections.value, catalogue.value))
const reasonProblem = computed(() => kickReasonProblem(reason.value))
const reasonError = computed(() => (submitted.value && reasonProblem.value ? t(`kick.reason.${reasonProblem.value}`) : ''))
// Exactly the target name: no trimming and no case folding, so a near miss never confirms.
const confirmed = computed(() => target.value !== '' && typed.value === target.value)
const canSubmit = computed(() => confirmed.value && !sending.value && !waiting.value)

watch([scope, target], () => {
  typed.value = ''
})
watch(scope, () => {
  target.value = ''
})

onMounted(async () => {
  if (!session.administrator) return
  const [list, services] = await Promise.all([api.sections(), api.cloudnetCatalogue()])
  if (list.ok) sections.value = list.data
  if (services.ok) catalogue.value = services.data
})
onBeforeUnmount(() => stopPolling())

async function submit(): Promise<void> {
  submitted.value = true
  if (!canSubmit.value || reasonProblem.value) return
  sending.value = true
  error.value = ''
  outcome.value = null
  timedOut.value = false
  key ??= newIdempotencyKey()
  const result = await api.kickMass({ scope: scope.value, target: target.value, reason: reason.value.trim() }, key)
  sending.value = false
  if (!result.ok) {
    error.value = result.error.message
    return
  }
  key = null
  waiting.value = true
  const id = result.data.id
  stopPolling = pollKick({
    fetch: () => api.kick(id),
    timer: props.timer,
    now: () => Date.now(),
    deadlineAt: result.data.deadlineAt,
    onDone(done) {
      waiting.value = false
      if (done.kind === 'settled') outcome.value = done.kick
      else if (done.kind === 'timeout') timedOut.value = true
      else error.value = done.message
    },
  })
}
</script>

<template>
  <section v-if="session.administrator" aria-labelledby="mass-kick-title" class="flex max-w-xl flex-col gap-3" data-testid="mass-kick">
    <h2 id="mass-kick-title" class="text-title-medium">{{ t('kick.mass.title') }}</h2>
    <p class="text-body-large text-on-surface-variant">{{ t('kick.mass.intro') }}</p>

    <form class="flex flex-col gap-4" novalidate @submit.prevent="submit">
      <div role="group" :aria-label="t('kick.scope.label')" class="flex flex-wrap gap-2">
        <MdChip
          v-for="value in SCOPES"
          :key="value"
          :label="t(`kick.scope.${value}`)"
          :selected="scope === value"
          :data-testid="`scope-${value}`"
          @click="scope = value"
        />
      </div>

      <MdSelect v-model="target" :label="t(`kick.target.${scope}`)" :placeholder="t('kick.target.choose')" :options="targets" data-testid="mass-target" />

      <MdTextField v-model="reason" :label="t('kick.reason.label')" :error="reasonError" :supporting-text="t('kick.reason.hint')" multiline :rows="2" data-testid="mass-reason" />

      <MdTextField
        v-model="typed"
        :label="t('kick.mass.confirmLabel')"
        :supporting-text="target ? t('kick.mass.confirmHint', { target }) : ''"
        :disabled="target === ''"
        data-testid="mass-confirm"
      />

      <MdProgress v-if="sending || waiting" :label="sending ? t('kick.sending') : t('kick.waiting')" />
      <div v-if="outcome" class="flex flex-wrap items-center gap-2" data-testid="mass-outcome">
        <KickStatusChip :status="outcome.status" />
        <span data-testid="mass-delivered">{{ t('kick.mass.result', { status: t(`kick.status.${outcome.status}`), count: outcome.deliveredCount }) }}</span>
      </div>
      <p v-if="timedOut" role="status" data-testid="mass-timeout">{{ t('kick.timedOut') }}</p>
      <p v-if="error" role="alert" data-testid="error" class="rounded-md-md bg-error-container p-3 text-on-error-container">{{ error }}</p>

      <div>
        <MdButton type="submit" variant="error" :disabled="!canSubmit" data-testid="mass-submit">{{ t('kick.mass.submit') }}</MdButton>
      </div>
    </form>
  </section>
</template>
