<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { ApiError, DiscordLinkStatus } from '../../api/types'
import { formatDateTime } from '../../format'
import MdButton from '../md/MdButton.vue'
import MdDialog from '../md/MdDialog.vue'

const { t, locale } = useI18n()
const api = useApi()

const status = ref<DiscordLinkStatus | null>(null)
const error = ref('')
const busy = ref(false)
/** The code as the backend formats it (`ABCD-EFGH`), null while none is live. */
const code = ref<string | null>(null)
const expiresAt = ref(0)
const remaining = ref(0)
const expired = ref(false)
/** Polite announcement (expiry, copy result); the countdown itself is never announced. */
const announcement = ref('')
const confirmingUnlink = ref(false)

let timer: ReturnType<typeof setInterval> | null = null

const command = computed(() => `/link ${code.value ?? ''}`)
const countdown = computed(() => {
  const minutes = Math.floor(remaining.value / 60)
  const seconds = remaining.value % 60
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
})

function stopTimer(): void {
  if (timer !== null) clearInterval(timer)
  timer = null
}

function tick(): void {
  remaining.value = Math.max(0, Math.ceil((expiresAt.value - Date.now()) / 1000))
  if (remaining.value > 0) return
  stopTimer()
  code.value = null
  expired.value = true
  announcement.value = t('account.discord.expired')
}

function fail(cause: ApiError): void {
  error.value = cause.message
}

async function loadStatus(): Promise<void> {
  const result = await api.discordLinkStatus()
  if (result.ok) status.value = result.data
  else fail(result.error)
}

async function issue(): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = ''
  announcement.value = ''
  const result = await api.issueDiscordLinkCode()
  busy.value = false
  if (!result.ok) return fail(result.error)
  code.value = result.data.code
  expiresAt.value = result.data.expiresAt
  expired.value = false
  stopTimer()
  tick()
  if (code.value !== null) timer = setInterval(tick, 1000)
}

async function copy(text: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text)
    announcement.value = t('account.discord.copied')
  } catch {
    announcement.value = t('account.discord.copyFailed')
  }
}

async function unlink(): Promise<void> {
  if (busy.value) return
  busy.value = true
  const result = await api.unlinkOwnDiscordLink()
  busy.value = false
  confirmingUnlink.value = false
  if (!result.ok) return fail(result.error)
  announcement.value = t('account.discord.unlinked')
  await loadStatus()
}

void loadStatus()
onBeforeUnmount(stopTimer)
</script>

<template>
  <section aria-labelledby="discord-link-heading" class="flex flex-col gap-4 border-t border-outline-variant pt-4" data-testid="discord-link">
    <h3 id="discord-link-heading" class="text-title-medium text-on-surface">{{ t('account.discord.heading') }}</h3>

    <p v-if="status" data-testid="discord-status" class="flex items-center gap-2 text-body-large text-on-surface">
      <svg aria-hidden="true" viewBox="0 0 24 24" class="size-5 shrink-0 fill-current">
        <path v-if="status.linked" d="M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4L9 16.2z" />
        <path v-else d="M19 6.4 17.6 5 12 10.6 6.4 5 5 6.4 10.6 12 5 17.6 6.4 19 12 13.4 17.6 19 19 17.6 13.4 12z" />
      </svg>
      <span v-if="status.linked">
        {{ t('account.discord.linked') }}
        <span v-if="status.linkedAt" class="text-on-surface-variant">({{ formatDateTime(status.linkedAt, locale) }})</span>
      </span>
      <span v-else>{{ t('account.discord.unlinkedStatus') }}</span>
    </p>

    <p class="text-body-medium text-on-surface-variant" data-testid="discord-access-hint">{{ t('account.discord.accessHint') }}</p>

    <div v-if="code" class="flex flex-col gap-3 rounded-md-md bg-surface-container-highest p-4">
      <div class="flex flex-wrap items-center gap-3">
        <p data-testid="discord-code" class="font-mono text-headline-large tracking-widest text-on-surface">{{ code }}</p>
        <MdButton variant="tonal" data-testid="discord-copy-code" @click="copy(code)">{{ t('account.discord.copyCode') }}</MdButton>
      </div>
      <p data-testid="discord-countdown" aria-live="off" class="text-body-medium">
        {{ t('account.discord.validFor', { time: countdown }) }}
      </p>
      <div>
        <p class="text-body-medium">{{ t('account.discord.instructions') }}</p>
        <div class="mt-1 flex flex-wrap items-center gap-3">
          <code data-testid="discord-command" class="font-mono text-body-large text-on-surface">{{ command }}</code>
          <MdButton variant="text" data-testid="discord-copy-command" @click="copy(command)">{{ t('account.discord.copyCommand') }}</MdButton>
        </div>
      </div>
    </div>

    <MdButton data-testid="discord-issue" class="self-start" :disabled="busy" @click="issue">
      {{ expired ? t('account.discord.reissue') : t('account.discord.issue') }}
    </MdButton>

    <MdButton v-if="status?.linked" variant="outlined" data-testid="discord-unlink" class="self-start" @click="confirmingUnlink = true">
      {{ t('account.discord.unlink') }}
    </MdButton>

    <!-- Always mounted so a screen reader announces the text when it appears. -->
    <p role="status" aria-live="polite" data-testid="discord-announcement" class="min-h-6 text-body-medium">{{ announcement }}</p>
    <p v-if="error" data-testid="discord-error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">{{ error }}</p>

    <MdDialog :open="confirmingUnlink" :headline="t('account.discord.unlinkTitle')" @close="confirmingUnlink = false">
      <p>{{ t('account.discord.unlinkBody') }}</p>
      <template #actions>
        <MdButton variant="text" data-testid="discord-unlink-cancel" @click="confirmingUnlink = false">{{ t('actions.cancel') }}</MdButton>
        <MdButton data-testid="discord-unlink-confirm" :disabled="busy" @click="unlink">{{ t('account.discord.unlinkConfirm') }}</MdButton>
      </template>
    </MdDialog>
  </section>
</template>
