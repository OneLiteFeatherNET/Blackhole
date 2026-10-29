<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { ApiError, NameHistoryName } from '../../api/types'
import { formatDateTime } from '../../format'
import MdButton from '../md/MdButton.vue'
import MdDataTable from '../md/MdDataTable.vue'
import ResourceState from '../ResourceState.vue'

const props = defineProps<{ owner: string }>()

const PAGE_SIZE = 50

const { t, locale } = useI18n()
const api = useApi()

// Held in memory only: the names of an account are never written to browser storage.
const names = ref<NameHistoryName[]>([])
const hasMore = ref(false)
const nextPage = ref(0)
const loading = ref(false)
const loaded = ref(false)
const error = ref<ApiError | null>(null)

async function load(page: number): Promise<void> {
  loading.value = true
  const result = await api.listPlayerNames(props.owner, page, PAGE_SIZE)
  loading.value = false
  if (!result.ok) {
    error.value = result.error
    return
  }
  error.value = null
  names.value = page === 0 ? result.data.names : [...names.value, ...result.data.names]
  hasMore.value = result.data.hasMore
  nextPage.value = page + 1
  loaded.value = true
}

const columns = computed(() => [
  { key: 'name' as const, label: t('player.names.columns.name') },
  { key: 'firstSeen' as const, label: t('player.names.columns.firstSeen') },
  { key: 'lastSeen' as const, label: t('player.names.columns.lastSeen') },
])

onMounted(() => load(0))
</script>

<template>
  <section aria-labelledby="names-heading" class="flex flex-col gap-3" data-testid="names-panel">
    <h2 id="names-heading" class="text-title-medium">{{ t('player.names.title') }}</h2>
    <p data-testid="names-history-note" class="text-body-medium text-on-surface-variant">{{ t('player.names.note') }}</p>

    <ResourceState :loading="loading" :error="error" :has-data="loaded" @retry="load(0)">
      <p v-if="names.length === 0" class="text-body-large text-on-surface-variant">{{ t('player.names.empty') }}</p>
      <MdDataTable v-else :label="t('player.names.title')" :columns="columns" :rows="names">
        <template #cell="{ row, column }">
          <template v-if="column.key === 'name'">{{ row.name }}</template>
          <template v-else-if="column.key === 'firstSeen'">{{ formatDateTime(row.firstSeen, locale) }}</template>
          <template v-else>{{ formatDateTime(row.lastSeen, locale) }}</template>
        </template>
      </MdDataTable>
    </ResourceState>

    <div v-if="hasMore" class="flex justify-center">
      <MdButton variant="outlined" data-testid="load-more" :disabled="loading" @click="load(nextPage)">
        {{ t('player.names.loadMore') }}
      </MdButton>
    </div>
  </section>
</template>
