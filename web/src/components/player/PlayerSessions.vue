<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { ApiError, SessionResponse } from '../../api/types'
import { formatDateTime } from '../../format'
import MdButton from '../md/MdButton.vue'
import MdDataTable from '../md/MdDataTable.vue'
import ResourceState from '../ResourceState.vue'

const props = defineProps<{ owner: string }>()

const PAGE_SIZE = 50
const DASH = '–'

const { t, locale } = useI18n()
const api = useApi()

// Held in memory only: the rows carry raw addresses and are never written to browser storage.
const sessions = ref<SessionResponse[]>([])
const hasMore = ref(false)
const nextPage = ref(0)
const loading = ref(false)
const loaded = ref(false)
const error = ref<ApiError | null>(null)

async function load(page: number): Promise<void> {
  loading.value = true
  const result = await api.playerSessions(props.owner, page, PAGE_SIZE)
  loading.value = false
  if (!result.ok) {
    error.value = result.error
    return
  }
  error.value = null
  sessions.value = page === 0 ? result.data.sessions : [...sessions.value, ...result.data.sessions]
  hasMore.value = result.data.hasMore
  nextPage.value = page + 1
  loaded.value = true
}

// The header names the player from the newest session; the UUID comes only from the sessions.
const identity = computed(() => (sessions.value[0] ? { name: sessions.value[0].playerName, uuid: sessions.value[0].playerUuid } : null))

const columns = computed(() => [
  { key: 'startedAt' as const, label: t('player.sessions.columns.when') },
  { key: 'outcome' as const, label: t('player.sessions.columns.outcome') },
  { key: 'ip' as const, label: t('player.sessions.columns.ip') },
  { key: 'asOrg' as const, label: t('player.sessions.columns.provider') },
  { key: 'countryCode' as const, label: t('player.sessions.columns.country') },
  { key: 'clientBrand' as const, label: t('player.sessions.columns.client') },
  { key: 'protocolVersion' as const, label: t('player.sessions.columns.protocol') },
  { key: 'virtualHost' as const, label: t('player.sessions.columns.host') },
])

const clientOf = (row: SessionResponse) => [row.clientBrand, row.clientVersion].filter(Boolean).join(' ') || DASH

onMounted(() => load(0))
</script>

<template>
  <section aria-labelledby="sessions-heading" class="flex flex-col gap-3" data-testid="sessions-panel">
    <h2 id="sessions-heading" class="text-title-medium">{{ t('player.sessions.title') }}</h2>
    <p v-if="identity" data-testid="sessions-identity" class="text-body-medium text-on-surface-variant">
      {{ identity.name }} · {{ identity.uuid }}
    </p>

    <ResourceState :loading="loading" :error="error" :has-data="loaded" @retry="load(0)">
      <p v-if="sessions.length === 0" class="text-body-large text-on-surface-variant">{{ t('player.sessions.empty') }}</p>
      <MdDataTable v-else :label="t('player.sessions.title')" :columns="columns" :rows="sessions">
        <template #cell="{ row, column }">
          <template v-if="column.key === 'startedAt'">{{ formatDateTime(row.startedAt, locale) }}</template>
          <template v-else-if="column.key === 'outcome'">{{ t(`player.sessions.outcome.${row.outcome}`) }}</template>
          <template v-else-if="column.key === 'asOrg'">{{ row.asOrg ?? DASH }}</template>
          <template v-else-if="column.key === 'countryCode'">
            {{ row.countryCode ? t('player.sessions.approximate', { country: row.countryCode }) : DASH }}
          </template>
          <template v-else-if="column.key === 'clientBrand'">{{ clientOf(row) }}</template>
          <template v-else-if="column.key === 'protocolVersion'">{{ row.protocolVersion ?? DASH }}</template>
          <template v-else-if="column.key === 'virtualHost'">{{ row.virtualHost ?? DASH }}</template>
          <template v-else>{{ row[column.key] }}</template>
        </template>
      </MdDataTable>
    </ResourceState>

    <div v-if="hasMore" class="flex justify-center">
      <MdButton variant="outlined" data-testid="load-more" :disabled="loading" @click="load(nextPage)">
        {{ t('player.sessions.loadMore') }}
      </MdButton>
    </div>

    <footer data-testid="sessions-attribution" class="text-label-medium text-on-surface-variant">
      {{ t('player.sessions.attribution') }}
      <a href="https://db-ip.com" target="_blank" rel="noopener noreferrer" class="underline">db-ip.com</a>
    </footer>
  </section>
</template>
