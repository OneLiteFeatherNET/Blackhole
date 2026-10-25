<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import type { Punishment } from '../../api/types'
import { formatDateTime } from '../../format'
import EvidenceDialog from '../evidence/EvidenceDialog.vue'
import MdButton from '../md/MdButton.vue'
import MdDataTable from '../md/MdDataTable.vue'
import StatusPill from '../StatusPill.vue'
import { countActive, isKick, reasonOf, stepLabel } from './punishmentInfo'

const props = withDefaults(defineProps<{ history: Punishment[]; now?: number }>(), { now: () => Date.now() })

// Kicks are history only: they get a badge and never count as active.
const active = computed(() => countActive(props.history, props.now))

const { t, locale } = useI18n()
const evidenceFor = ref<string | null>(null)

const columns = computed(() => [
  { key: 'createdAt' as const, label: t('player.columns.when') },
  { key: 'section' as const, label: t('player.columns.section') },
  { key: 'source' as const, label: t('player.columns.issuedBy') },
  { key: 'template' as const, label: t('player.columns.reason') },
  { key: 'stepIndex' as const, label: t('player.columns.step') },
  { key: 'eloCost' as const, label: t('player.columns.cost') },
  { key: 'lifted' as const, label: t('player.columns.status') },
  { key: 'identifier' as const, label: t('player.columns.evidence') },
  { key: 'shortId' as const, label: t('case.title') },
])
</script>

<template>
  <p v-if="history.length === 0" class="text-body-large text-on-surface-variant">{{ t('player.noHistory') }}</p>
  <p v-if="history.length > 0" class="mb-2 text-body-medium text-on-surface-variant" data-testid="active-count">{{ t('player.activeCount', { count: active }) }}</p>
  <MdDataTable v-if="history.length > 0" :label="t('player.history')" :columns="columns" :rows="history">
    <template #cell="{ row, column }">
      <template v-if="column.key === 'createdAt'">{{ row.createdAt ? formatDateTime(row.createdAt, locale) : '' }}</template>
      <template v-else-if="column.key === 'source'">{{ row.actorName || row.source }}</template>
      <template v-else-if="column.key === 'template'">{{ reasonOf(row) }}</template>
      <template v-else-if="column.key === 'stepIndex'">{{ stepLabel(row) ?? '' }}</template>
      <template v-else-if="column.key === 'eloCost'">{{ row.eloCost ?? '' }}</template>
      <template v-else-if="column.key === 'lifted'">
        <StatusPill v-if="isKick(row)" :label="t('kick.badge')" tone="tertiary" data-testid="kick-badge" />
        <StatusPill v-else-if="row.lifted" :label="t('player.lifted')" />
        <StatusPill v-else-if="row.permanent" :label="t('player.permanent')" tone="error" />
        <StatusPill v-else :label="t('player.ended')" />
      </template>
      <template v-else-if="column.key === 'identifier'">
        <MdButton variant="text" :data-testid="`open-evidence-${row.identifier}`" @click="evidenceFor = row.identifier">
          {{ t('evidence.open') }}
        </MdButton>
      </template>
      <RouterLink
        v-else-if="column.key === 'shortId'"
        :to="{ name: 'case', params: { reference: row.identifier } }"
        class="text-primary underline underline-offset-2 outline-none focus-visible:outline-2 focus-visible:outline-primary"
        :data-testid="`open-case-${row.identifier}`"
      >
        {{ t('case.open') }}
      </RouterLink>
      <template v-else>{{ row[column.key] }}</template>
    </template>
  </MdDataTable>
  <EvidenceDialog v-if="evidenceFor" open :punishment="evidenceFor" @close="evidenceFor = null" />
</template>
