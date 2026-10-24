<script setup lang="ts">
import { computed, inject, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { MAX_TEXT_LENGTH, type ErrorCode } from '../api/contract'
import { formatDate } from '../format'
import { SESSION_KEY } from '../session'

const emit = defineEmits<{ back: []; filed: [] }>()

const { t, te, locale } = useI18n()
const session = inject(SESSION_KEY)!
const drafts = session.state.drafts
const sending = ref(false)
const failure = ref<{ code: ErrorCode; availableFrom?: string } | null>(null)

const length = computed(() => drafts.statement.trim().length)
const tooLong = computed(() => length.value > MAX_TEXT_LENGTH)
const blank = computed(() => length.value === 0)

const failureText = computed(() => {
  const current = failure.value
  if (!current) return ''
  if (current.code === 'NOT_ELIGIBLE') {
    // Only a plain wait has a date; an open appeal or a punishment that is over has none.
    return current.availableFrom ? t('appealForm.errors.NOT_ELIGIBLE', { date: formatDate(current.availableFrom, locale.value) }) : t('appealForm.errors.NOT_ELIGIBLE_NOW')
  }
  return te(`appealForm.errors.${current.code}`) ? t(`appealForm.errors.${current.code}`) : t('appealForm.errors.UNAVAILABLE')
})

async function submit() {
  if (blank.value || tooLong.value || sending.value) return
  sending.value = true
  failure.value = null
  try {
    const result = await session.api.fileAppeal(drafts.statement.trim())
    if (!session.alive(result)) return
    if (result.ok) {
      drafts.statement = ''
      await session.refreshPunishment()
      emit('filed')
      return
    }
    failure.value = result.error
    // The state may have changed under us (forfeited, frozen, already open): show the current one.
    if (['FORFEITED', 'FROZEN', 'NOT_ELIGIBLE', 'CONFLICT'].includes(result.error.code)) await session.refreshPunishment()
  } finally {
    sending.value = false
  }
}
</script>

<template>
  <section aria-labelledby="appeal-form-title" class="rounded-md-lg bg-surface-container p-6">
    <h1 id="appeal-form-title" class="text-headline-small text-on-surface">{{ t('appealForm.title') }}</h1>
    <p class="mt-2 text-body-large text-on-surface-variant">{{ t('appealForm.notice') }}</p>

    <form class="mt-6 flex flex-col gap-4" novalidate @submit.prevent="submit">
      <div class="flex flex-col gap-1">
        <label for="statement" class="text-label-large text-on-surface">{{ t('appealForm.label') }}</label>
        <textarea
          id="statement"
          v-model="drafts.statement"
          rows="8"
          required
          aria-describedby="statement-hint statement-count"
          :aria-invalid="tooLong"
          class="plain-text rounded-md-xs border border-outline bg-surface p-4 text-body-large text-on-surface focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
        />
        <p id="statement-hint" class="text-body-small text-on-surface-variant">{{ t('appealForm.hint') }}</p>
        <p id="statement-count" :class="tooLong ? 'text-error' : 'text-on-surface-variant'" class="text-body-small">
          {{ t('appealForm.counter', { count: length, max: MAX_TEXT_LENGTH }) }}
        </p>
      </div>

      <div role="alert" aria-live="assertive">
        <p v-if="failure" class="rounded-md-sm bg-error-container px-4 py-3 text-body-medium text-on-error-container">{{ failureText }}</p>
      </div>

      <div class="flex flex-wrap gap-3">
        <button
          type="submit"
          :disabled="blank || tooLong || sending"
          class="md-state inline-flex h-10 items-center justify-center rounded-md-full bg-primary px-6 text-label-large text-on-primary disabled:opacity-60"
        >
          {{ sending ? t('appealForm.sending') : t('appealForm.submit') }}
        </button>
        <button
          type="button"
          class="md-state inline-flex h-10 items-center justify-center rounded-md-full px-6 text-label-large text-primary"
          @click="emit('back')"
        >
          {{ t('common.back') }}
        </button>
      </div>
    </form>
  </section>
</template>
