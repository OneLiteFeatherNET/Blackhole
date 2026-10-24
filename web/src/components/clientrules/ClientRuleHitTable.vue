<script setup lang="ts">
// The hits of a rule or of a player. A hit says which rule matched whom, when and what the rule did; it
// never carries what the client sent, so there is nothing of that kind to show.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import type { ClientRuleHit } from '../../api/types'
import { formatDateTime, shortHash } from '../../format'
import { useLookupStore } from '../../stores/lookup'
import MdDataTable from '../md/MdDataTable.vue'
import StatusPill from '../StatusPill.vue'

const props = defineProps<{
  hits: ClientRuleHit[]
  /** `player` lists the hits of one rule (who), `rule` the hits of one player (which rule). */
  by: 'player' | 'rule'
  label: string
}>()

const { t, locale } = useI18n()
const lookup = useLookupStore()

const columns = computed(() => [
  { key: (props.by === 'player' ? 'owner' : 'rule') as 'owner' | 'rule', label: props.by === 'player' ? t('clientRules.hits.columns.player') : t('clientRules.hits.columns.rule') },
  { key: 'at' as const, label: t('clientRules.hits.columns.at') },
  { key: 'action' as const, label: t('clientRules.hits.columns.action') },
  { key: 'enforced' as const, label: t('clientRules.hits.columns.status') },
  { key: 'server' as const, label: t('clientRules.hits.columns.server') },
])

const nameOf = (owner: string) => lookup.nameOf(owner) ?? shortHash(owner)
// Bypassed staff are never refused; a refusal needs the proxy to have enforced it; otherwise the team was only told.
const statusOf = (hit: ClientRuleHit) => (hit.bypassed ? 'bypassed' : hit.enforced ? 'enforced' : 'notified')
</script>

<template>
  <MdDataTable :label="label" :columns="columns" :rows="hits">
    <template #cell="{ row, column }">
      <template v-if="column.key === 'owner' || column.key === 'rule'">
        <RouterLink v-if="by === 'player'" :to="`/players/${row.owner}`" :title="row.owner" class="text-primary underline underline-offset-2">
          {{ nameOf(row.owner) }}
        </RouterLink>
        <span v-else>{{ row.rule }}</span>
      </template>
      <template v-else-if="column.key === 'at'">{{ formatDateTime(Date.parse(row.at), locale) }}</template>
      <template v-else-if="column.key === 'action'">{{ t(`clientRules.action.${row.action}`) }}</template>
      <template v-else-if="column.key === 'enforced'">
        <StatusPill :label="t(`clientRules.hits.status.${statusOf(row)}`)" :tone="statusOf(row) === 'enforced' ? 'error' : 'neutral'" />
      </template>
      <template v-else>
        <span v-if="row.server">{{ row.server }}</span>
        <span v-else class="text-on-surface-variant" aria-hidden="true">-</span>
      </template>
    </template>
  </MdDataTable>
</template>
