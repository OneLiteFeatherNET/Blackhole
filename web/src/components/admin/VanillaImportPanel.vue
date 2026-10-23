<script setup lang="ts">
import { computed, ref, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { VanillaImportResult } from '../../api/types'
import MdButton from '../md/MdButton.vue'
import MdDialog from '../md/MdDialog.vue'
import MdProgress from '../md/MdProgress.vue'

// The UI server forwards request bodies up to 1 MiB on this route; larger files are refused here
// with an explanation instead of failing later with a bare 413.
const MAX_BYTES = 1024 * 1024

const { t } = useI18n()
const api = useApi()

const playersFile = ref<File | null>(null)
const ipsFile = ref<File | null>(null)
const pasted = ref('')
const confirming = ref(false)
const running = ref(false)
const problem = ref('')
const result = ref<VanillaImportResult | null>(null)
const announcement = ref('')

const playersFileId = useId()
const ipsFileId = useId()
const pasteId = useId()
const pasteHintId = useId()

const hasPlayers = computed(() => pasted.value.trim() !== '' || playersFile.value !== null)

function pick(event: Event, target: 'players' | 'ips'): void {
  const file = (event.target as HTMLInputElement).files?.[0] ?? null
  if (target === 'players') playersFile.value = file
  else ipsFile.value = file
}

async function run(dryRun: boolean): Promise<void> {
  if (running.value) return
  const players: Blob | null = pasted.value.trim() !== '' ? new Blob([pasted.value], { type: 'application/json' }) : playersFile.value
  if (!players) return
  problem.value = ''
  if (players.size > MAX_BYTES || (ipsFile.value?.size ?? 0) > MAX_BYTES) {
    problem.value = t('vanillaImport.tooLarge')
    announcement.value = problem.value
    return
  }
  running.value = true
  result.value = null
  announcement.value = ''
  const response = await api.importVanillaBans({ players, ips: ipsFile.value, dryRun })
  running.value = false
  confirming.value = false
  if (!response.ok) {
    problem.value = response.error.status === 413 ? t('vanillaImport.tooLarge') : response.error.message
    announcement.value = t('vanillaImport.failedAnnounce')
    return
  }
  result.value = response.data
  announcement.value = t(response.data.dryRun ? 'vanillaImport.previewAnnounce' : 'vanillaImport.importAnnounce', {
    imported: response.data.playersImported,
    skipped: response.data.playersSkippedExisting,
    errors: response.data.playersInvalid,
  })
}
</script>

<template>
  <section aria-labelledby="vanilla-import-title" class="flex flex-col gap-4" data-testid="vanilla-import">
    <h2 id="vanilla-import-title" class="text-title-large">{{ t('vanillaImport.title') }}</h2>
    <p class="text-body-large text-on-surface-variant">{{ t('vanillaImport.intro') }}</p>

    <div class="flex flex-col gap-1">
      <label :for="playersFileId" class="text-label-large">{{ t('vanillaImport.playersFile') }}</label>
      <input :id="playersFileId" type="file" accept=".json,application/json" data-testid="players-file" class="text-body-medium" @change="pick($event, 'players')" />
    </div>

    <div class="flex flex-col gap-1">
      <label :for="pasteId" class="text-label-large">{{ t('vanillaImport.paste') }}</label>
      <textarea
        :id="pasteId"
        v-model="pasted"
        rows="6"
        spellcheck="false"
        data-testid="paste"
        :aria-describedby="pasteHintId"
        class="w-full rounded-md-xs border border-outline bg-transparent p-4 font-mono text-body-small text-on-surface outline-none hover:border-on-surface focus:border-2 focus:outline-2 focus:-outline-offset-2 focus:outline-primary"
      />
      <p :id="pasteHintId" class="px-1 text-body-small text-on-surface-variant">{{ t('vanillaImport.pasteHint') }}</p>
    </div>

    <div class="flex flex-col gap-1">
      <label :for="ipsFileId" class="text-label-large">{{ t('vanillaImport.ipsFile') }}</label>
      <input :id="ipsFileId" type="file" accept=".json,application/json" data-testid="ips-file" class="text-body-medium" @change="pick($event, 'ips')" />
      <p class="px-1 text-body-small text-on-surface-variant">{{ t('vanillaImport.ipsHint') }}</p>
    </div>

    <div class="flex flex-wrap gap-2">
      <MdButton variant="outlined" data-testid="preview" :disabled="!hasPlayers || running" @click="run(true)">{{ t('vanillaImport.preview') }}</MdButton>
      <MdButton data-testid="import" :disabled="!hasPlayers || running" @click="confirming = true">{{ t('vanillaImport.import') }}</MdButton>
    </div>
    <MdProgress v-if="running" :label="t('vanillaImport.running')" />

    <!-- Always mounted so a screen reader announces the outcome when it appears. -->
    <p role="status" aria-live="polite" class="sr-only" data-testid="announcement">{{ announcement }}</p>
    <p v-if="problem" role="alert" data-testid="problem" class="rounded-md-md bg-error-container p-3 text-on-error-container">{{ problem }}</p>

    <div v-if="result" data-testid="result" class="flex flex-col gap-3">
      <h3 class="text-title-medium">{{ result.dryRun ? t('vanillaImport.resultPreview') : t('vanillaImport.resultImport') }}</h3>
      <dl class="grid grid-cols-[auto_1fr] gap-x-6 gap-y-1 text-body-medium">
        <dt>{{ t('vanillaImport.total') }}</dt>
        <dd data-testid="total">{{ result.playersTotal }}</dd>
        <dt>{{ result.dryRun ? t('vanillaImport.wouldImport') : t('vanillaImport.imported') }}</dt>
        <dd data-testid="imported">{{ result.playersImported }}</dd>
        <dt>{{ t('vanillaImport.skipped') }}</dt>
        <dd data-testid="skipped">{{ result.playersSkippedExisting }}</dd>
        <dt>{{ t('vanillaImport.errors') }}</dt>
        <dd data-testid="errors">{{ result.playersInvalid }}</dd>
        <template v-if="result.ipsTotal > 0">
          <dt>{{ t('vanillaImport.ips') }}</dt>
          <dd data-testid="ips">{{ t('vanillaImport.ipsSkipped', { total: result.ipsTotal, skipped: result.ipsSkipped }) }}</dd>
        </template>
      </dl>
      <div v-if="result.invalidEntries.length > 0">
        <h4 class="text-title-small">{{ t('vanillaImport.invalidEntries') }}</h4>
        <ul class="list-disc pl-5 text-body-small" data-testid="invalid-entries">
          <li v-for="(entry, index) in result.invalidEntries" :key="index">{{ entry }}</li>
        </ul>
      </div>
    </div>
  </section>

  <MdDialog :open="confirming" :headline="t('vanillaImport.confirmTitle')" @close="confirming = false">
    <p>{{ t('vanillaImport.confirmBody') }}</p>
    <template #actions>
      <MdButton variant="text" data-testid="cancel" @click="confirming = false">{{ t('actions.cancel') }}</MdButton>
      <MdButton data-testid="confirm" :disabled="running" @click="run(false)">{{ t('vanillaImport.confirm') }}</MdButton>
    </template>
  </MdDialog>
</template>
