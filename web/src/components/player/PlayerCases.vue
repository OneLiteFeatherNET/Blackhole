<script setup lang="ts">
// The cases of the player, each linking to its own case page. The case list keeps its own filters.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { useApi } from '../../api/inject'
import type { CaseListItem } from '../../api/types'
import { useResource } from '../../composables/useResource'
import { formatDateTime } from '../../format'
import ResourceState from '../ResourceState.vue'

const props = defineProps<{ owner: string }>()

const { t, locale } = useI18n()
const api = useApi()
const cases = useResource(() => api.cases({ owner: props.owner }))
const rows = computed<CaseListItem[]>(() => cases.data.value?.items ?? [])
</script>

<template>
  <section aria-labelledby="cases-heading">
    <h2 id="cases-heading" class="mb-3 text-title-medium">{{ t('player.tabs.cases') }}</h2>
    <ResourceState :loading="cases.loading.value" :error="cases.error.value" :has-data="cases.data.value !== null" @retry="cases.reload">
      <p v-if="rows.length === 0" class="text-body-large text-on-surface-variant">{{ t('player.cases.empty') }}</p>
      <ul v-else data-testid="player-cases" class="flex flex-col gap-2">
        <li v-for="kase in rows" :key="kase.punishmentIdentifier" class="flex flex-wrap items-center gap-3">
          <RouterLink
            :to="{ name: 'case', params: { reference: kase.punishmentIdentifier } }"
            class="inline-flex min-h-12 items-center text-primary underline underline-offset-2 focus-ring"
          >
            {{ kase.shortId }}
          </RouterLink>
          <span class="text-body-medium text-on-surface-variant">{{ t(`case.status.${kase.status}`) }} · {{ formatDateTime(kase.lastActivityAt, locale) }}</span>
        </li>
      </ul>
    </ResourceState>
  </section>
</template>
