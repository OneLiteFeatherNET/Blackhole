<script setup lang="ts">
// Shows one piece of case evidence to the player. A chat capture is cut to up to 20 lines chosen by
// the moderator, with the appealing speaker shown as "You"; the backend's preview is what the player
// will see, and nothing is shared until the moderator ticks the confirmation. A screenshot is shared
// whole and needs the stronger confirmation. The active shares of the item can be withdrawn here.
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { Evidence, EvidenceShare, EvidenceSharePreview, ShareRevokeReason } from '../../api/types'
import { formatDateTime } from '../../format'
import { useLookupStore } from '../../stores/lookup'
import { chatLinesOf } from './payload'
import MdButton from '../md/MdButton.vue'
import MdCheckbox from '../md/MdCheckbox.vue'
import MdDialog from '../md/MdDialog.vue'
import MdSelect from '../md/MdSelect.vue'
import ReasonDialog from '../case/ReasonDialog.vue'

/** The most lines one share may show; the backend's default `blackhole.chat.share.max-lines`. */
const MAX_SHARE_LINES = 20
const REVOKE_REASONS: ShareRevokeReason[] = ['MISTAKE', 'PRIVACY', 'OTHER']
const OWNER_HASH = /^[0-9a-f]{128}$/i

const props = defineProps<{ open: boolean; item: Evidence; reference: string }>()
const emit = defineEmits<{ close: []; done: [] }>()

const { t, locale } = useI18n()
const api = useApi()
const lookup = useLookupStore()

const isChat = computed(() => props.item.kind === 'CHAT_LOG')
const isImage = computed(() => props.item.kind === 'FILE')
const lines = computed(() => chatLinesOf(props.item))

const selection = ref<number[]>([])
const appellant = ref('')
const confirmed = ref(false)
const preview = ref<EvidenceSharePreview | null>(null)
const previewError = ref('')
const submitting = ref(false)
const error = ref('')
const imageUrl = ref<string | null>(null)
const imageFailed = ref(false)
const shares = ref<EvidenceShare[]>([])
const revoking = ref<EvidenceShare | null>(null)
const announcement = ref('')

/** Speakers in order of first appearance; a name the session knows is used, an unknown owner hash becomes "Player N". */
const speakerLabels = computed(() => {
  const labels = new Map<string, string>()
  let unknown = 0
  for (const { speaker } of lines.value) {
    if (speaker === null || labels.has(speaker)) continue
    const name = lookup.nameOf(speaker)
    if (name) labels.set(speaker, name)
    else if (OWNER_HASH.test(speaker)) labels.set(speaker, t('evidence.chat.player', { n: ++unknown }))
    else labels.set(speaker, speaker)
  }
  return labels
})
const speakerOptions = computed(() => [...speakerLabels.value].map(([value, label]) => ({ value, label })))

const activeShares = computed(() => shares.value.filter((share) => share.evidenceId === props.item.identifier && share.state === 'ACTIVE'))

const shareable = computed(() => {
  if (submitting.value || !confirmed.value) return false
  if (isImage.value) return true
  return isChat.value && selection.value.length > 0 && appellant.value !== '' && preview.value !== null
})

function timeOf(timestamp: number | string | null): string {
  if (timestamp === null) return ''
  if (typeof timestamp === 'number') return formatDateTime(timestamp, locale.value)
  const parsed = Date.parse(timestamp)
  return Number.isNaN(parsed) ? timestamp : formatDateTime(parsed, locale.value)
}

function setLine(index: number, checked: boolean): void {
  const without = selection.value.filter((line) => line !== index)
  selection.value = checked ? [...without, index].sort((a, b) => a - b) : without
}

const limitReached = (index: number): boolean => !selection.value.includes(index) && selection.value.length >= MAX_SHARE_LINES

// Any change of the selection needs a new look at the preview and a new confirmation.
let previewGeneration = 0
async function loadPreview(): Promise<void> {
  const mine = ++previewGeneration
  preview.value = null
  previewError.value = ''
  if (!isChat.value || selection.value.length === 0 || appellant.value === '') return
  const result = await api.previewEvidenceShare(props.reference, {
    evidenceId: props.item.identifier,
    selection: [...selection.value],
    appellantSpeaker: appellant.value,
  })
  if (mine !== previewGeneration) return
  if (result.ok) preview.value = result.data
  else previewError.value = result.error.message
}

watch([selection, appellant], () => {
  confirmed.value = false
  void loadPreview()
})

async function loadShares(): Promise<void> {
  const result = await api.evidenceShares(props.reference)
  if (result.ok) shares.value = result.data
}

async function loadImage(): Promise<void> {
  if (!isImage.value) return
  const result = await api.evidenceContent(props.item.identifier)
  if (result.ok && typeof URL.createObjectURL === 'function') imageUrl.value = URL.createObjectURL(result.data)
  else imageFailed.value = true
}

function releaseImage(): void {
  if (imageUrl.value && typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(imageUrl.value)
  imageUrl.value = null
  imageFailed.value = false
}

watch(
  () => props.open,
  (open) => {
    if (!open) {
      releaseImage()
      return
    }
    selection.value = []
    appellant.value = ''
    confirmed.value = false
    preview.value = null
    previewError.value = ''
    error.value = ''
    void loadShares()
    void loadImage()
  },
  { immediate: true },
)

onBeforeUnmount(releaseImage)

async function share(): Promise<void> {
  if (!shareable.value) return
  submitting.value = true
  error.value = ''
  const result = await api.shareEvidence(
    props.reference,
    isChat.value
      ? { evidenceId: props.item.identifier, selection: [...selection.value], appellantSpeaker: appellant.value, confirmedMinimised: true }
      : { evidenceId: props.item.identifier, confirmedMinimised: true },
  )
  submitting.value = false
  if (!result.ok) {
    error.value = result.error.message || t('evidence.share.failed')
    return
  }
  announcement.value = t('evidence.share.done')
  emit('done')
  await loadShares()
}

async function revoked(): Promise<void> {
  revoking.value = null
  announcement.value = t('evidence.share.withdrawn')
  await loadShares()
}
</script>

<template>
  <MdDialog :open="open" :headline="t('evidence.share.title')" wide @close="emit('close')">
    <div class="flex flex-col gap-5 pt-2">
      <p class="text-body-medium text-on-surface-variant">{{ isImage ? t('evidence.share.imageIntro') : t('evidence.share.chatIntro') }}</p>
      <p role="status" aria-live="polite" class="sr-only" data-testid="share-status">{{ announcement }}</p>

      <template v-if="isChat">
        <fieldset class="flex flex-col gap-2">
          <legend class="text-label-large text-on-surface">{{ t('evidence.share.lines') }}</legend>
          <p class="text-body-small text-on-surface-variant" data-testid="share-limit">{{ t('evidence.share.limit', { max: MAX_SHARE_LINES }) }}</p>
          <ol class="flex flex-col gap-1">
            <li v-for="(line, index) in lines" :key="index">
              <label class="flex cursor-pointer flex-wrap items-baseline gap-2 text-body-medium">
                <input
                  type="checkbox"
                  class="accent-primary"
                  :data-testid="`share-line-${index}`"
                  :checked="selection.includes(index)"
                  :disabled="limitReached(index)"
                  @change="setLine(index, ($event.target as HTMLInputElement).checked)"
                />
                <span class="text-body-small text-on-surface-variant">{{ timeOf(line.timestamp) }}</span>
                <span class="font-medium">{{ line.speaker === null ? '' : (speakerLabels.get(line.speaker) ?? line.speaker) }}</span>
                <span class="break-words">{{ line.text }}</span>
              </label>
            </li>
          </ol>
        </fieldset>

        <MdSelect
          data-testid="share-speaker"
          :label="t('evidence.share.speaker')"
          :supporting-text="t('evidence.share.speakerHint')"
          :placeholder="t('evidence.share.speakerPlaceholder')"
          :options="speakerOptions"
          :disabled="selection.length === 0"
          v-model="appellant"
        />

        <section class="flex flex-col gap-2" :aria-label="t('evidence.share.preview')" data-testid="share-preview">
          <h4 class="text-title-small text-on-surface">{{ t('evidence.share.preview') }}</h4>
          <p v-if="preview === null && previewError" role="alert" class="text-body-medium text-error">{{ previewError }}</p>
          <p v-else-if="preview === null" class="text-body-medium text-on-surface-variant" data-testid="share-preview-empty">{{ t('evidence.share.previewEmpty') }}</p>
          <ol v-else class="flex flex-col gap-1 rounded-md-md bg-surface-container-low p-3">
            <li v-for="(line, index) in preview.lines" :key="index" class="flex flex-wrap gap-2 text-body-medium">
              <span class="text-body-small text-on-surface-variant">+{{ line.offsetSeconds ?? 0 }} s</span>
              <span class="font-medium">{{ line.speaker }}</span>
              <span class="break-words">{{ line.text }}</span>
            </li>
          </ol>
        </section>
      </template>

      <figure v-else class="flex flex-col gap-2">
        <img v-if="imageUrl" :src="imageUrl" :alt="t('evidence.share.imageAlt')" class="max-h-96 w-auto max-w-full rounded-md-md object-contain" data-testid="share-image" />
        <p v-else-if="imageFailed" class="text-body-medium text-error">{{ t('evidence.share.imageFailed') }}</p>
      </figure>

      <MdCheckbox
        v-model="confirmed"
        data-testid="share-confirm"
        :label="isImage ? t('evidence.share.confirmImage') : t('evidence.share.confirmChat')"
        :supporting-text="isImage ? t('evidence.share.confirmImageHint') : t('evidence.share.confirmChatHint')"
      />
      <p v-if="error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container" data-testid="share-error">{{ error }}</p>

      <section class="flex flex-col gap-2" :aria-label="t('evidence.share.active')" data-testid="shares">
        <h4 class="text-title-small text-on-surface">{{ t('evidence.share.active') }}</h4>
        <p v-if="activeShares.length === 0" class="text-body-medium text-on-surface-variant">{{ t('evidence.share.none') }}</p>
        <ul v-else class="flex flex-col gap-2">
          <li v-for="row in activeShares" :key="row.shareId" class="flex flex-wrap items-center gap-2" data-testid="share-row">
            <span class="text-body-medium">{{ t('evidence.share.since', { date: formatDateTime(row.sharedAt, locale) }) }}</span>
            <MdButton variant="text" :data-testid="`share-revoke-${row.shareId}`" @click="revoking = row">{{ t('evidence.share.withdraw') }}</MdButton>
          </li>
        </ul>
      </section>
    </div>
    <template #actions>
      <MdButton variant="text" data-testid="share-cancel" @click="emit('close')">{{ t('actions.cancel') }}</MdButton>
      <MdButton data-testid="share-submit" :disabled="!shareable" @click="share">{{ t('evidence.share.submit') }}</MdButton>
    </template>
  </MdDialog>

  <ReasonDialog
    :open="revoking !== null"
    name="revoke-share"
    :headline="t('evidence.share.withdrawTitle')"
    :body="t('evidence.share.withdrawBody')"
    :confirm-label="t('evidence.share.withdrawConfirm')"
    :reasons="REVOKE_REASONS"
    reason-prefix="evidence.share.reason"
    :run="(reason: string) => api.revokeEvidenceShare(reference, revoking?.shareId ?? '', reason as ShareRevokeReason)"
    @close="revoking = null"
    @done="revoked"
  />
</template>
