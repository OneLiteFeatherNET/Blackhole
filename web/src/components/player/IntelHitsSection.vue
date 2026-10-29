<script setup lang="ts">
// The VPN and hosting logins of one player, newest first. Only a caller with a staff role asks for them and sees them;
// the address itself is never shown here.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { IntelHit } from '../../api/types'
import { canRead } from '../../auth/permissions'
import { useResource } from '../../composables/useResource'
import { formatDateTime } from '../../format'
import { useSessionStore } from '../../stores/session'
import MdDataTable from '../md/MdDataTable.vue'
import MdProgress from '../md/MdProgress.vue'
import StatusPill from '../StatusPill.vue'

const props = defineProps<{ owner: string }>()

const { t, locale } = useI18n()
const api = useApi()
const session = useSessionStore()

const staff = computed(() => canRead(session.roles))
const hits = useResource<IntelHit[]>(() =>
  staff.value ? api.intelHits(props.owner) : Promise.resolve({ ok: true as const, data: [] as IntelHit[] }),
)

const columns = computed(() => [
  { key: 'createdAt' as const, label: t('intel.hits.columns.time') },
  { key: 'classification' as const, label: t('intel.hits.columns.class') },
  { key: 'sourceKey' as const, label: t('intel.hits.columns.source') },
  { key: 'verdict' as const, label: t('intel.hits.columns.verdict') },
  { key: 'allowListed' as const, label: t('intel.hits.columns.allowListed') },
])

const rows = computed<IntelHit[]>(() => [...(hits.data.value ?? [])].sort((a, b) => b.createdAt - a.createdAt))
// Whether the owner was exempt at the newest login; the list does not say whether the exemption still runs.
const newest = computed(() => rows.value[0] ?? null)

const tones = { ALLOW: 'primary', FLAG: 'tertiary', DENY: 'error' } as const

function classesOf(hit: IntelHit): string {
  return hit.classification
    .split(',')
    .map((name) => t(`intel.classes.${name.trim()}`))
    .join(', ')
}
</script>

<template>
  <div v-if="staff" class="flex flex-col gap-3" data-testid="intel-hits">
    <p class="text-body-medium text-on-surface-variant">{{ t('intel.hits.hint') }}</p>
    <div aria-live="polite" class="flex flex-col gap-3">
      <MdProgress v-if="hits.loading.value && !hits.data.value" :label="t('state.loading')" />
      <p v-else-if="hits.error.value" data-testid="intel-error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">
        {{ hits.error.value.message }}
      </p>
      <template v-else-if="hits.data.value">
        <p v-if="rows.length === 0" data-testid="intel-empty" class="text-body-large text-on-surface-variant">{{ t('intel.hits.empty') }}</p>
        <template v-else>
          <p data-testid="intel-allow-list-state" class="text-body-medium">
            {{ newest?.allowListed ? t('intel.hits.allowListedYes') : t('intel.hits.allowListedNo') }}
          </p>
          <MdDataTable :label="t('intel.hits.title')" :columns="columns" :rows="rows">
            <template #cell="{ row, column }">
              <template v-if="column.key === 'createdAt'">{{ formatDateTime(row.createdAt, locale) }}</template>
              <template v-else-if="column.key === 'classification'">{{ classesOf(row) }}</template>
              <template v-else-if="column.key === 'verdict'">
                <StatusPill :label="t(`intel.policies.${row.verdict}`)" :tone="tones[row.verdict]" />
              </template>
              <template v-else-if="column.key === 'allowListed'">{{ row.allowListed ? t('intel.hits.yes') : t('intel.hits.no') }}</template>
              <template v-else>{{ row.sourceKey }}</template>
            </template>
          </MdDataTable>
        </template>
      </template>
    </div>
  </div>
</template>
