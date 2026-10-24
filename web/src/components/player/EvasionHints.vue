<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { useApi } from '../../api/inject'
import type { EvasionLinkedOwner } from '../../api/types'
import { useResource } from '../../composables/useResource'
import { formatDateTime, shortHash } from '../../format'
import { useLookupStore } from '../../stores/lookup'
import MdDataTable from '../md/MdDataTable.vue'
import MdProgress from '../md/MdProgress.vue'
import StatusPill from '../StatusPill.vue'

const props = defineProps<{ owner: string }>()

const { t, locale } = useI18n()
const api = useApi()
const lookup = useLookupStore()

// Hashes only: the backend never sends an IP, and this panel never asks for one.
const sightings = useResource(() => api.evasionSightings(props.owner))

const columns = computed(() => [
  { key: 'owner' as const, label: t('player.evasion.columns.owner') },
  { key: 'via' as const, label: t('player.evasion.columns.via') },
  { key: 'sharedAddresses' as const, label: t('player.evasion.columns.shared') },
  { key: 'firstSeen' as const, label: t('player.evasion.columns.firstSeen') },
  { key: 'lastSeen' as const, label: t('player.evasion.columns.lastSeen') },
])

const rows = computed<EvasionLinkedOwner[]>(() => sightings.data.value?.linkedOwners ?? [])
const nameOf = (owner: string) => lookup.nameOf(owner) ?? shortHash(owner)
// IP and BOTH are the strong signals; the client setup alone is shared by a modpack or a launcher too, so it is only a weak hint.
const weakOnly = computed(() => rows.value.length > 0 && rows.value.every((row) => row.via === 'CLIENT'))
const hasWeak = computed(() => rows.value.some((row) => row.via === 'CLIENT'))
const bothOff = computed(() => sightings.data.value?.detectionEnabled === false && sightings.data.value?.clientFingerprintEnabled !== true)
</script>

<template>
  <div class="flex flex-col gap-3" data-testid="evasion-panel">
    <p class="text-body-medium text-on-surface-variant">{{ t('player.evasion.hint') }}</p>
    <div aria-live="polite" class="flex flex-col gap-3">
      <MdProgress v-if="sightings.loading.value && !sightings.data.value" :label="t('state.loading')" />
      <p
        v-else-if="sightings.error.value"
        data-testid="evasion-error"
        role="alert"
        class="rounded-md-md bg-error-container p-3 text-on-error-container"
      >
        {{ sightings.error.value.message }}
      </p>
      <p
        v-else-if="sightings.data.value && bothOff"
        data-testid="evasion-disabled"
        class="rounded-md-md bg-surface-container-high p-3 text-on-surface"
      >
        {{ t('player.evasion.disabled') }}
      </p>
      <p v-else-if="sightings.data.value && rows.length === 0" data-testid="evasion-empty" class="text-body-large text-on-surface-variant">
        {{ t('player.evasion.empty', { days: sightings.data.value.windowDays ?? 0 }) }}
      </p>
      <template v-else-if="sightings.data.value">
        <MdDataTable :label="t('player.evasion.title')" :columns="columns" :rows="rows">
          <template #cell="{ row, column }">
            <RouterLink
              v-if="column.key === 'owner'"
              :to="`/players/${row.owner}`"
              :title="row.owner"
              class="text-primary underline underline-offset-2"
            >
              {{ nameOf(row.owner) }}
            </RouterLink>
            <template v-else-if="column.key === 'via'">
              <StatusPill :label="t(`player.evasion.via.${row.via}`)" :tone="row.via === 'CLIENT' ? 'tertiary' : 'primary'" />
              <span v-if="row.via === 'CLIENT'" class="ml-2 text-body-small text-on-surface-variant" data-testid="weak-hint">{{ t('player.evasion.weak') }}</span>
            </template>
            <template v-else-if="column.key === 'sharedAddresses'">{{ row.sharedAddresses ?? 0 }}</template>
            <template v-else>{{ formatDateTime((column.key === 'firstSeen' ? row.firstSeen : row.lastSeen) ?? 0, locale) }}</template>
          </template>
        </MdDataTable>
        <p v-if="hasWeak" data-testid="evasion-weak-note" class="text-body-medium text-on-surface-variant">
          {{ weakOnly ? t('player.evasion.weakOnly') : t('player.evasion.weakNote') }}
        </p>
        <p v-if="sightings.data.value.truncated" data-testid="evasion-truncated" class="text-body-medium text-on-surface-variant">
          {{ t('player.evasion.truncated', { count: rows.length }) }}
        </p>
      </template>
    </div>
  </div>
</template>
