<script setup lang="ts">
import type { AltchaWidgetElement } from 'altcha'
import { computed, inject, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ChallengePurpose } from '../api/contract'
import { SESSION_KEY } from '../session'

/**
 * The proof-of-work check in front of sign-in and filing. The widget has no visual puzzle: a labelled checkbox
 * that solves itself when the form loads (`auto="onload"`). Its solution goes into the form's hidden `altcha`
 * field, which the parent reads with {@link read}. A failed solve shows a retry control; a check that cannot run
 * in this browser shows a message, and the parent keeps its submit button off until a solution exists.
 */
const props = defineProps<{ purpose: ChallengePurpose }>()
const emit = defineEmits<{ ready: [ready: boolean] }>()

const { t, locale } = useI18n()
const session = inject(SESSION_KEY)!

type Phase = 'checking' | 'off' | 'solving' | 'solved' | 'failed' | 'unavailable'
const phase = ref<Phase>('checking')
const widget = ref<AltchaWidgetElement | null>(null)

const challengeUrl = computed(() => `/api/challenge?purpose=${props.purpose}`)
const showWidget = computed(() => phase.value === 'solving' || phase.value === 'solved' || phase.value === 'failed')
const language = computed(() => (locale.value === 'de' ? 'de' : 'en'))

function setPhase(next: Phase) {
  phase.value = next
  // Ready: the check is off, or a solution exists. Everything else keeps the submit button off.
  emit('ready', next === 'off' || next === 'solved')
}

onMounted(async () => {
  const result = await session.api.challenge(props.purpose)
  if (!result.ok) {
    setPhase('unavailable')
    return
  }
  if ('enabled' in result.data && result.data.enabled === false) {
    setPhase('off')
    return
  }
  setPhase('solving')
})

/** Maps the widget's state to the phase. Only `verified` counts as a solution. */
function onStateChange(event: Event) {
  const state = (event as CustomEvent<{ state?: string }>).detail?.state
  if (state === 'verified') setPhase('solved')
  else if (state === 'error') setPhase('failed')
  else if (state === 'expired') {
    widget.value?.reset()
    setPhase('solving')
  } else if (state === 'verifying' || state === 'unverified') setPhase('solving')
}

/** The solved payload from the widget's hidden field, or null when there is none. */
function read(): string | null {
  const form = widget.value?.closest('form') ?? document
  const field = form.querySelector<HTMLInputElement>('input[name="altcha"]')
  return field && field.value !== '' ? field.value : null
}

/** A fresh challenge for the next attempt: the solution of a sent request is spent, so the widget starts again. */
function renew() {
  if (phase.value === 'off' || phase.value === 'unavailable' || phase.value === 'checking') return
  widget.value?.reset()
  setPhase('solving')
  void widget.value?.verify()
}

function retry() {
  renew()
}

defineExpose({ read, renew })
</script>

<template>
  <div class="flex flex-col gap-2" data-testid="bot-challenge" :data-phase="phase">
    <altcha-widget
      v-if="showWidget"
      ref="widget"
      :challenge="challengeUrl"
      name="altcha"
      type="checkbox"
      auto="onload"
      :language="language"
      configuration='{"humanInteractionSignature":false}'
      @statechange="onStateChange"
    />
    <p v-if="phase === 'checking' || phase === 'solving'" role="status" aria-live="polite" class="text-body-small text-on-surface-variant">
      {{ phase === 'checking' ? t('challenge.checking') : t('challenge.solving') }}
    </p>
    <p v-else-if="phase === 'solved'" role="status" aria-live="polite" class="text-body-small text-on-surface-variant">
      {{ t('challenge.solved') }}
    </p>
    <div v-else-if="phase === 'failed'" class="flex flex-col gap-2">
      <p role="alert" class="rounded-md-sm bg-error-container px-4 py-3 text-body-medium text-on-error-container">{{ t('challenge.failed') }}</p>
      <div>
        <button type="button" class="md-state inline-flex h-10 items-center rounded-md-full border border-outline px-6 text-label-large text-primary" @click="retry">
          {{ t('challenge.retry') }}
        </button>
      </div>
    </div>
    <p v-else-if="phase === 'unavailable'" role="alert" class="rounded-md-sm bg-error-container px-4 py-3 text-body-medium text-on-error-container">
      {{ t('challenge.unsupported') }}
    </p>
  </div>
</template>
