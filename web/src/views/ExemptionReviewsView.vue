<script setup lang="ts">
// The administrator's queue of automatic punishments that were held back for protected players. Each review is decided
// once, with a justification; a decided review leaves the open list.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../api/inject'
import type { ExemptionReview } from '../api/types'
import ExemptionBadge from '../components/exemption/ExemptionBadge.vue'
import ExemptionDecisionDialog, { type ExemptionDecision } from '../components/exemption/ExemptionDecisionDialog.vue'
import DataTable, { type DataColumn } from '../components/DataTable.vue'
import EmptyState from '../components/EmptyState.vue'
import PageShell from '../components/PageShell.vue'
import ResourceState from '../components/ResourceState.vue'
import TextLink from '../components/TextLink.vue'
import MdButton from '../components/md/MdButton.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import { useResource } from '../composables/useResource'
import { formatDateTime, shortHash } from '../format'

const { t, locale } = useI18n()
const api = useApi()

const reviews = useResource(() => api.exemptionReviews())
const deciding = ref<{ review: ExemptionReview; action: ExemptionDecision } | null>(null)
const message = ref('')

const open = computed<ExemptionReview[]>(() => reviews.data.value ?? [])

const columns = computed<DataColumn<ExemptionReview>[]>(() => [
  { key: 'owner', label: t('exemptionReviews.columns.player'), priority: 1 },
  { key: 'section', label: t('exemptionReviews.columns.section'), priority: 2 },
  { key: 'tier', label: t('exemptionReviews.columns.track'), priority: 2 },
  { key: 'scoreAfter', label: t('exemptionReviews.columns.score'), priority: 2, align: 'end' },
  { key: 'crossedAt', label: t('exemptionReviews.columns.crossed'), priority: 3 },
  { key: 'id', label: t('exemptionReviews.columns.decision'), priority: 1, align: 'end' },
])

/** The list is read again after a decision, so the list shows what the backend holds. */
async function decided(text: string): Promise<void> {
  deciding.value = null
  message.value = text
  await reviews.reload()
}
</script>

<template>
  <PageShell :title="t('exemptionReviews.title')">
    <p class="mb-4 text-body-medium text-on-surface-variant">{{ t('exemptionReviews.intro') }}</p>

    <DataTable
      v-if="reviews.loading.value && reviews.data.value === null"
      :caption="t('exemptionReviews.title')"
      :columns="columns"
      :rows="[]"
      loading
    />

    <ResourceState :loading="reviews.loading.value" :error="reviews.error.value" :has-data="reviews.data.value !== null" @retry="reviews.reload">
      <template v-if="reviews.data.value">
        <DataTable v-if="open.length > 0" :caption="t('exemptionReviews.title')" :columns="columns" :rows="open">
          <template #cell="{ row, column }">
            <div v-if="column.key === 'owner'" class="flex flex-wrap items-center gap-2">
              <TextLink :to="`/players/${row.owner}`" :title="row.owner">{{ shortHash(row.owner) }}</TextLink>
              <ExemptionBadge :owner="row.owner" />
            </div>
            <template v-else-if="column.key === 'crossedAt'">{{ formatDateTime(row.crossedAt, locale) }}</template>
            <div v-else-if="column.key === 'id'" class="flex flex-wrap items-center justify-end gap-2">
              <MdButton variant="text" :data-testid="`dismiss-${row.id}`" @click="deciding = { review: row, action: 'dismiss' }">
                {{ t('exemptionReviews.dismiss.open') }}
              </MdButton>
              <MdButton variant="tonal" :data-testid="`apply-${row.id}`" @click="deciding = { review: row, action: 'apply' }">
                {{ t('exemptionReviews.apply.open') }}
              </MdButton>
            </div>
            <template v-else>{{ row[column.key as keyof ExemptionReview] }}</template>
          </template>
        </DataTable>

        <EmptyState v-else data-testid="exemption-reviews-empty" variant="no-items" :title="t('exemptionReviews.empty')" />
      </template>
    </ResourceState>
  </PageShell>

  <ExemptionDecisionDialog
    :open="deciding !== null"
    :review="deciding?.review ?? null"
    :action="deciding?.action ?? null"
    @close="deciding = null"
    @done="decided"
  />
  <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
</template>
