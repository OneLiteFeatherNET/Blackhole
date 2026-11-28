<script setup lang="ts">
// One blocklist the backend refreshes: its attribution and licence are always shown; the refresh status only when the
// backend reports one (none is reported yet, so the card says so).
import { useI18n } from 'vue-i18n'
import { formatDateTime } from '../../format'
import MdCard from '../md/MdCard.vue'
import StatusPill from '../StatusPill.vue'
import type { ListSource } from './listSources'

export type SourceStatus = 'FRESH' | 'STALE' | 'FAILED'

const props = defineProps<{
  source: ListSource
  status: SourceStatus | null
  /** The last successful refresh, shown next to a failed one. */
  lastSuccessAt: number | null
}>()

const { t, locale } = useI18n()

const tones = { FRESH: 'primary', STALE: 'tertiary', FAILED: 'error' } as const
</script>

<template>
  <MdCard variant="outlined" data-testid="list-source-card" class="flex flex-col gap-2 p-4">
    <h3 class="text-title-medium">{{ t(`intel.sources.${source.kind}`) }}</h3>
    <p data-testid="source-attribution" class="text-body-medium text-on-surface-variant break-words">{{ source.attribution }}</p>
    <p data-testid="source-licence" class="text-body-small text-on-surface-variant">{{ t('intel.source.licence', { licence: source.licence }) }}</p>
    <div class="flex flex-wrap items-center gap-2">
      <StatusPill
        v-if="status"
        data-testid="source-status"
        :label="t(`intel.source.status.${status}`)"
        :tone="tones[status]"
      />
      <span v-else data-testid="source-status" class="text-body-medium text-on-surface-variant">{{ t('intel.source.notReported') }}</span>
    </div>
    <p v-if="status === 'FAILED' && lastSuccessAt !== null" data-testid="source-last-ok" class="text-body-small text-on-surface-variant">
      {{ t('intel.source.lastOk', { when: formatDateTime(lastSuccessAt, locale) }) }}
    </p>
  </MdCard>
</template>
