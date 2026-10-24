<script setup lang="ts">
// The stored protocol of every decided appeal of the case: the versions (the current one and the ones
// it replaced), the text of one version, and a download as text or JSON. Staff from VIEWER up may read
// it, and the backend records every opening and download on the case. The text is shown as plain text
// (mustache in a <pre>, never v-html) and is held only in this component, never in the store or in
// browser storage.
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { ApiError, CaseProtocolVersion, TranscriptFile, TranscriptFormat } from '../../api/types'
import { formatDateTime } from '../../format'
import MdButton from '../md/MdButton.vue'
import ResourceState from '../ResourceState.vue'
import StatusPill from '../StatusPill.vue'

const props = defineProps<{ reference: string; shortId: string }>()

const { t, locale } = useI18n()
const api = useApi()

const versions = ref<CaseProtocolVersion[]>([])
const decidedAppeals = ref<string[]>([])
const loaded = ref(false)
const loading = ref(false)
const error = ref<ApiError | null>(null)
const announcement = ref('')

const openId = ref<string | null>(null)
const openText = ref('')
const busy = ref<string | null>(null)
const actionError = ref('')

/** A decided appeal without any readable version: the protocol is not made yet, or a redaction is being finished. */
const pending = computed(() => {
  const covered = new Set(versions.value.map((version) => version.appealId))
  return decidedAppeals.value.some((appealId) => !covered.has(appealId))
})

let generation = 0

async function reload(): Promise<void> {
  const mine = ++generation
  loading.value = true
  const [protocols, decided] = await Promise.all([
    api.caseProtocols(props.reference),
    api.caseDecidedAppeals(props.reference),
  ])
  if (mine !== generation) return
  loading.value = false
  if (!protocols.ok) {
    error.value = protocols.error
    return
  }
  error.value = null
  loaded.value = true
  versions.value = protocols.data
  // The notice is a hint: when the decisions cannot be read, the list still shows what is stored.
  decidedAppeals.value = decided.ok ? decided.data : []
  if (openId.value !== null && !protocols.data.some((version) => version.evidenceId === openId.value)) closeText()
}

function closeText(): void {
  openId.value = null
  openText.value = ''
}

async function toggle(version: CaseProtocolVersion): Promise<void> {
  if (busy.value !== null) return
  if (openId.value === version.evidenceId) {
    closeText()
    return
  }
  busy.value = version.evidenceId
  actionError.value = ''
  const result = await api.openCaseProtocol(props.reference, version.evidenceId)
  busy.value = null
  if (!result.ok) {
    actionError.value = result.error.message
    return
  }
  openId.value = version.evidenceId
  openText.value = result.data.text
  announcement.value = t('case.protocol.opened')
}

function save(file: TranscriptFile, version: number): void {
  const blob = new Blob([file.content], { type: `${file.mediaType};charset=utf-8` })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `case-${props.shortId.replace(/^#/, '')}-protocol-v${version}.${file.format === 'json' ? 'json' : 'txt'}`
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}

async function download(version: CaseProtocolVersion, format: TranscriptFormat): Promise<void> {
  if (busy.value !== null) return
  busy.value = version.evidenceId
  actionError.value = ''
  const result = await api.downloadCaseProtocol(props.reference, version.evidenceId, format)
  busy.value = null
  if (!result.ok) {
    actionError.value = result.error.message
    return
  }
  save(result.data, version.version)
  announcement.value = t('case.protocol.downloaded')
}

onMounted(() => void reload())
watch(
  () => props.reference,
  () => {
    closeText()
    void reload()
  },
)

defineExpose({ reload })
</script>

<template>
  <section id="case-protocol" :aria-label="t('case.protocol.title')" class="flex flex-col gap-3" data-testid="protocol-section">
    <h3 class="text-title-medium">{{ t('case.protocol.title') }}</h3>
    <p class="text-body-medium text-on-surface-variant">{{ t('case.protocol.hint') }}</p>
    <p role="status" aria-live="polite" class="sr-only" data-testid="protocol-status">{{ announcement }}</p>

    <ResourceState :loading="loading" :error="error" :has-data="loaded" @retry="reload">
      <p
        v-if="pending"
        role="status"
        class="rounded-md-md bg-surface-container-highest p-3 text-on-surface"
        data-testid="protocol-pending"
      >
        {{ t('case.protocol.pending') }}
      </p>
      <p v-if="versions.length === 0 && !pending" class="text-body-medium text-on-surface-variant" data-testid="protocol-none">
        {{ t('case.protocol.none') }}
      </p>

      <ul v-if="versions.length > 0" class="flex flex-col gap-3" data-testid="protocol-versions">
        <li
          v-for="version in versions"
          :key="version.evidenceId"
          class="flex flex-col gap-2 rounded-md-md bg-surface-container p-4"
          data-testid="protocol-version"
        >
          <div class="flex flex-wrap items-center gap-3">
            <span class="text-title-small">{{ t('case.protocol.version', { version: version.version }) }}</span>
            <StatusPill
              :label="version.current ? t('case.protocol.current') : t('case.protocol.superseded')"
              :tone="version.current ? 'primary' : 'neutral'"
              data-testid="protocol-state"
            />
            <span class="text-body-medium text-on-surface-variant">{{ t('case.protocol.entries', { count: version.entryCount }) }}</span>
            <span class="text-body-medium text-on-surface-variant">{{ t('case.protocol.storedAt', { when: formatDateTime(version.createdAt, locale) }) }}</span>
          </div>
          <p v-if="!version.current" class="text-body-small text-on-surface-variant">{{ t('case.protocol.supersededHint') }}</p>
          <div class="flex flex-wrap gap-2">
            <MdButton
              variant="outlined"
              :disabled="busy !== null"
              :aria-expanded="openId === version.evidenceId"
              :aria-label="t(openId === version.evidenceId ? 'case.protocol.closeLabel' : 'case.protocol.openLabel', { version: version.version })"
              data-testid="protocol-open"
              @click="toggle(version)"
            >
              {{ t(openId === version.evidenceId ? 'case.protocol.close' : 'case.protocol.open') }}
            </MdButton>
            <MdButton
              variant="text"
              :disabled="busy !== null"
              :aria-label="t('case.protocol.downloadTextLabel', { version: version.version })"
              data-testid="protocol-download-text"
              @click="download(version, 'text')"
            >
              {{ t('case.protocol.downloadText') }}
            </MdButton>
            <MdButton
              variant="text"
              :disabled="busy !== null"
              :aria-label="t('case.protocol.downloadJsonLabel', { version: version.version })"
              data-testid="protocol-download-json"
              @click="download(version, 'json')"
            >
              {{ t('case.protocol.downloadJson') }}
            </MdButton>
          </div>
          <pre
            v-if="openId === version.evidenceId"
            tabindex="0"
            role="region"
            :aria-label="t('case.protocol.textLabel', { version: version.version })"
            class="max-h-96 overflow-auto whitespace-pre-wrap break-words rounded-md-md bg-surface-container-highest p-3 font-mono text-body-small text-on-surface"
            data-testid="protocol-text"
          >{{ openText }}</pre>
        </li>
      </ul>
    </ResourceState>

    <p v-if="actionError" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container" data-testid="protocol-error">{{ actionError }}</p>
  </section>
</template>
