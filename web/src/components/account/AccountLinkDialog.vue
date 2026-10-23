<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { ApiError, StaffLinkStatus } from '../../api/types'
import { formatDateTime } from '../../format'
import MdButton from '../md/MdButton.vue'
import MdDialog from '../md/MdDialog.vue'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: [] }>()

const { t, locale } = useI18n()
const api = useApi()

const status = ref<StaffLinkStatus | null>(null)
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

const command = computed(() => `/blackhole link ${code.value ?? ''}`)
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
  announcement.value = t('account.link.expired')
}

function fail(cause: ApiError): void {
  error.value = cause.message
}

async function loadStatus(): Promise<void> {
  const result = await api.staffLinkStatus()
  if (result.ok) status.value = result.data
  else fail(result.error)
}

async function issue(): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = ''
  announcement.value = ''
  const result = await api.issueStaffLinkCode()
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
    announcement.value = t('account.link.copied')
  } catch {
    announcement.value = t('account.link.copyFailed')
  }
}

async function unlink(): Promise<void> {
  if (busy.value) return
  busy.value = true
  const result = await api.unlinkOwnStaffLink()
  busy.value = false
  confirmingUnlink.value = false
  if (!result.ok) return fail(result.error)
  announcement.value = t('account.link.unlinked')
  await loadStatus()
}

watch(
  () => props.open,
  async (open) => {
    stopTimer()
    confirmingUnlink.value = false
    if (!open) return
    status.value = null
    error.value = ''
    code.value = null
    expired.value = false
    announcement.value = ''
    await loadStatus()
  },
  { immediate: true },
)

onBeforeUnmount(stopTimer)
</script>

<template>
  <MdDialog :open="open" :headline="t('account.link.title')" @close="emit('close')">
    <div class="flex flex-col gap-4">
      <p v-if="status" data-testid="link-status" class="flex items-center gap-2 text-body-large text-on-surface">
        <svg aria-hidden="true" viewBox="0 0 24 24" class="size-5 shrink-0 fill-current">
          <path v-if="status.linked" d="M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4L9 16.2z" />
          <path v-else d="M19 6.4 17.6 5 12 10.6 6.4 5 5 6.4 10.6 12 5 17.6 6.4 19 12 13.4 17.6 19 19 17.6 13.4 12z" />
        </svg>
        <span v-if="status.linked">
          {{ t('account.link.linked', { uuid: status.minecraftUuid ?? '' }) }}
          <span v-if="status.linkedAt" class="text-on-surface-variant">({{ formatDateTime(status.linkedAt, locale) }})</span>
        </span>
        <span v-else>{{ t('account.link.unlinkedStatus') }}</span>
      </p>

      <div v-if="code" class="flex flex-col gap-3 rounded-md-md bg-surface-container-highest p-4">
        <div class="flex flex-wrap items-center gap-3">
          <p data-testid="code" class="font-mono text-headline-large tracking-widest text-on-surface">{{ code }}</p>
          <MdButton variant="tonal" data-testid="copy-code" @click="copy(code)">{{ t('account.link.copyCode') }}</MdButton>
        </div>
        <p data-testid="countdown" aria-live="off" class="text-body-medium">
          {{ t('account.link.validFor', { time: countdown }) }}
        </p>
        <div>
          <p class="text-body-medium">{{ t('account.link.instructions') }}</p>
          <div class="mt-1 flex flex-wrap items-center gap-3">
            <code data-testid="command" class="font-mono text-body-large text-on-surface">{{ command }}</code>
            <MdButton variant="text" data-testid="copy-command" @click="copy(command)">{{ t('account.link.copyCommand') }}</MdButton>
          </div>
        </div>
      </div>

      <MdButton data-testid="issue" class="self-start" :disabled="busy" @click="issue">
        {{ expired ? t('account.link.reissue') : t('account.link.issue') }}
      </MdButton>

      <MdButton v-if="status?.linked" variant="outlined" data-testid="unlink" class="self-start" @click="confirmingUnlink = true">
        {{ t('account.link.unlink') }}
      </MdButton>

      <!-- Always mounted so a screen reader announces the text when it appears. -->
      <p role="status" aria-live="polite" data-testid="announcement" class="min-h-6 text-body-medium">{{ announcement }}</p>
      <p v-if="error" data-testid="error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">{{ error }}</p>
    </div>
    <template #actions>
      <MdButton variant="text" data-testid="close" @click="emit('close')">{{ t('account.link.close') }}</MdButton>
    </template>

    <MdDialog :open="confirmingUnlink" :headline="t('account.link.unlinkTitle')" @close="confirmingUnlink = false">
      <p>{{ t('account.link.unlinkBody') }}</p>
      <template #actions>
        <MdButton variant="text" data-testid="unlink-cancel" @click="confirmingUnlink = false">{{ t('actions.cancel') }}</MdButton>
        <MdButton data-testid="unlink-confirm" :disabled="busy" @click="unlink">{{ t('account.link.unlinkConfirm') }}</MdButton>
      </template>
    </MdDialog>
  </MdDialog>
</template>
