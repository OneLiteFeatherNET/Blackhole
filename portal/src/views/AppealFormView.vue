<script setup lang="ts">
import { computed, inject, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { MAX_FILES, MAX_TEXT_LENGTH, type AppealConditions, type ErrorCode } from '../api/contract'
import { stageDraft, useAttachments } from '../chat/attachments'
import { ACCEPT, releasePreview } from '../chat/files'
import ConditionsText from '../components/ConditionsText.vue'
import FileChips from '../components/FileChips.vue'
import { formatDate } from '../format'
import { SESSION_KEY } from '../session'

const emit = defineEmits<{ back: []; filed: [] }>()

const { t, te, locale } = useI18n()
const session = inject(SESSION_KEY)!
const drafts = session.state.drafts
const { notice, addFiles, removeFile } = useAttachments(session, { upload: false })
const input = ref<HTMLInputElement | null>(null)
const sending = ref(false)
const failure = ref<{ code: ErrorCode; availableFrom?: string } | null>(null)
/** The conditions shown, and the player's confirmation of exactly this version. Never remembered between visits. */
const conditions = ref<AppealConditions | null>(null)
const conditionsFailed = ref(false)
const confirmed = ref(false)

async function loadConditions() {
  const result = await session.api.appealConditions()
  if (!session.alive(result)) return
  conditionsFailed.value = !result.ok
  if (result.ok) conditions.value = result.data
}

onMounted(loadConditions)

const length = computed(() => drafts.statement.trim().length)
const tooLong = computed(() => length.value > MAX_TEXT_LENGTH)
const blank = computed(() => length.value === 0)
const canSend = computed(() => !blank.value && !tooLong.value && !sending.value && confirmed.value && conditions.value !== null)

const failureText = computed(() => {
  const current = failure.value
  if (!current) return ''
  if (current.code === 'NOT_ELIGIBLE') {
    // Only a plain wait has a date; an open appeal or a punishment that is over has none.
    return current.availableFrom ? t('appealForm.errors.NOT_ELIGIBLE', { date: formatDate(current.availableFrom, locale.value) }) : t('appealForm.errors.NOT_ELIGIBLE_NOW')
  }
  return te(`appealForm.errors.${current.code}`) ? t(`appealForm.errors.${current.code}`) : t('appealForm.errors.UNAVAILABLE')
})

function onPick(event: Event) {
  const target = event.target as HTMLInputElement
  addFiles(target.files)
  target.value = ''
}

/**
 * After the filing: the picked files are uploaded and sent as one message, so the player does not
 * need a second send. A file that fails stays in the composer as a failed chip; the filing stands.
 * Staged files stay in the composer too when the message is refused. False when the session ended.
 */
async function sendPicked(): Promise<boolean> {
  for (const file of drafts.files.filter((candidate) => candidate.state === 'picked')) {
    file.state = 'uploading'
    if (!(await stageDraft(session, file.key, file.source!))) return false
  }
  const staged = drafts.files.filter((file) => file.state === 'staged')
  if (staged.length === 0) return true
  const result = await session.api.postMessage(undefined, crypto.randomUUID(), staged.map((file) => file.attachmentId!))
  if (!session.alive(result)) return false
  if (result.ok) {
    for (const file of staged) releasePreview(file)
    drafts.files = drafts.files.filter((file) => file.state !== 'staged')
  }
  return true
}

async function submit() {
  const shown = conditions.value
  if (!canSend.value || shown === null) return
  sending.value = true
  failure.value = null
  try {
    const result = await session.api.fileAppeal(drafts.statement.trim(), shown.version)
    if (!session.alive(result)) return
    if (result.ok) {
      drafts.statement = ''
      await session.refreshPunishment()
      if (!(await sendPicked())) return
      emit('filed')
      return
    }
    failure.value = result.error
    if (result.error.code === 'CONDITIONS_CHANGED') {
      // Show the new text and ask again: the confirmation was for an older version.
      confirmed.value = false
      await loadConditions()
      return
    }
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

    <div class="mt-6 rounded-md-lg bg-surface-container-high p-6" data-testid="conditions">
      <h2 class="text-title-large text-on-surface">{{ t('appealForm.conditions.title') }}</h2>
      <div class="mt-3">
        <ConditionsText v-if="conditions" :text="conditions.text" />
        <p v-else-if="conditionsFailed" role="alert" class="text-body-medium text-error">{{ t('appealForm.conditions.loadFailed') }}</p>
        <p v-else role="status" class="text-body-medium text-on-surface-variant">{{ t('app.loading') }}</p>
      </div>
    </div>

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

      <div class="flex flex-col gap-2" data-testid="appeal-files">
        <div class="flex flex-wrap items-center gap-3">
          <input ref="input" type="file" multiple :accept="ACCEPT" class="hidden" data-testid="file-input" @change="onPick" />
          <button
            type="button"
            class="md-state inline-flex h-10 items-center justify-center rounded-md-full border border-outline px-6 text-label-large text-primary disabled:opacity-60"
            :disabled="drafts.files.length >= MAX_FILES"
            aria-describedby="file-count appeal-files-hint"
            @click="input?.click()"
          >
            {{ t('chat.attach') }}
          </button>
          <p id="file-count" class="text-body-small text-on-surface-variant">{{ t('chat.fileCount', { count: drafts.files.length, max: MAX_FILES }) }}</p>
        </div>
        <p id="appeal-files-hint" class="text-body-small text-on-surface-variant">{{ t('appealForm.filesHint') }}</p>
        <FileChips :files="drafts.files" @remove="removeFile" />
        <p v-if="notice" role="alert" class="rounded-md-sm bg-error-container px-4 py-3 text-body-medium text-on-error-container">{{ notice }}</p>
      </div>

      <div class="flex items-start gap-3">
        <input id="conditions-confirm" v-model="confirmed" type="checkbox" :disabled="conditions === null" class="mt-1 size-5 accent-primary" />
        <label for="conditions-confirm" class="text-body-large text-on-surface">{{ t('appealForm.conditions.confirm') }}</label>
      </div>

      <div role="alert" aria-live="assertive">
        <p v-if="failure" class="rounded-md-sm bg-error-container px-4 py-3 text-body-medium text-on-error-container">{{ failureText }}</p>
      </div>

      <div class="flex flex-wrap gap-3">
        <button
          type="submit"
          :disabled="!canSend"
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
