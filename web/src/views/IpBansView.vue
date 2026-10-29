<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../api/inject'
import type { IpBan } from '../api/types'
import DataTable, { type DataColumn } from '../components/DataTable.vue'
import EmptyState from '../components/EmptyState.vue'
import FilterToolbar, { type FilterDef } from '../components/FilterToolbar.vue'
import PageShell from '../components/PageShell.vue'
import ResourceState from '../components/ResourceState.vue'
import RowMenu from '../components/RowMenu.vue'
import StatusPill from '../components/StatusPill.vue'
import IpBanCreateDialog from '../components/ipban/IpBanCreateDialog.vue'
import IpBanLiftDialog from '../components/ipban/IpBanLiftDialog.vue'
import { banState, type BanState } from '../components/ipban/ipBanRules'
import MdButton from '../components/md/MdButton.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import { useResource } from '../composables/useResource'
import { formatDateTime, shortHash } from '../format'
import { useSessionStore } from '../stores/session'

const { t, locale } = useI18n()
const api = useApi()
const session = useSessionStore()

// Active is the default: the bans that hold right now. "Lifted" is every ban that no longer holds (lifted or expired).
const values = ref<Record<string, string>>({ state: '' })
const showLifted = computed(() => values.value.state === 'lifted')

const filters = computed<FilterDef[]>(() => [
  {
    kind: 'toggle-group',
    id: 'state',
    label: t('ipBans.filter.label'),
    options: [
      { value: '', label: t('ipBans.filter.active'), testid: 'filter-active' },
      { value: 'lifted', label: t('ipBans.filter.lifted'), testid: 'filter-lifted' },
    ],
  },
])

const bans = useResource(() => api.ipBans())
const creating = ref(false)
const lifting = ref<IpBan | null>(null)
const message = ref('')

const columns = computed<DataColumn<IpBanRow>[]>(() => [
  { key: 'range', label: t('ipBans.columns.range'), priority: 1 },
  { key: 'reason', label: t('ipBans.columns.reason'), priority: 1 },
  { key: 'state', label: t('ipBans.columns.state'), priority: 1 },
  { key: 'source', label: t('ipBans.columns.source'), priority: 3 },
  { key: 'createdAt', label: t('ipBans.columns.created'), priority: 2 },
  { key: 'expiresAt', label: t('ipBans.columns.expires'), priority: 2 },
  { key: 'actorOid', label: t('ipBans.columns.actor'), priority: 3 },
  { key: 'id', label: t('ipBans.columns.actions'), priority: 1, align: 'end' },
])

// A ban row with its state, so the table and the actions read the same answer.
type IpBanRow = IpBan & { state: BanState }

const now = Date.now()
const visible = computed<IpBanRow[]>(() =>
  (bans.data.value ?? [])
    .map((ban) => ({ ...ban, state: banState(ban, now) }))
    .filter((row) => (showLifted.value ? row.state !== 'active' : row.state === 'active')),
)

async function done(text: string): Promise<void> {
  creating.value = false
  lifting.value = null
  message.value = text
  await bans.reload()
}
</script>

<template>
  <PageShell :title="t('ipBans.title')" :description="t('ipBans.intro')">
    <template #actions>
      <MdButton v-if="session.moderator" data-testid="new-ip-ban" @click="creating = true">{{ t('ipBans.new') }}</MdButton>
    </template>

    <p v-if="!session.moderator" data-testid="read-only" class="text-body-medium text-on-surface-variant">{{ t('ipBans.readOnly') }}</p>

    <template #toolbar>
      <FilterToolbar v-model="values" :filters="filters" />
    </template>

    <DataTable v-if="bans.loading.value && bans.data.value === null" :caption="t('ipBans.title')" :columns="columns" :rows="[]" loading />

    <ResourceState :loading="bans.loading.value" :error="bans.error.value" :has-data="bans.data.value !== null" @retry="bans.reload">
      <template v-if="bans.data.value">
        <DataTable v-if="visible.length > 0" :caption="t('ipBans.title')" :columns="columns" :rows="visible">
          <template #cell="{ row, column }">
            <span v-if="column.key === 'range'" class="break-all text-label-large">{{ row.range }}</span>
            <p v-else-if="column.key === 'reason'" class="max-w-xs break-words">{{ row.reason }}</p>
            <StatusPill
              v-else-if="column.key === 'state'"
              :label="t(`ipBans.state.${row.state}`)"
              :tone="row.state === 'active' ? 'tertiary' : 'neutral'"
            />
            <template v-else-if="column.key === 'source'">{{ t(`ipBans.source.${row.source}`) }}</template>
            <template v-else-if="column.key === 'createdAt'">{{ formatDateTime(row.createdAt, locale) }}</template>
            <template v-else-if="column.key === 'expiresAt'">{{ row.expiresAt ? formatDateTime(row.expiresAt, locale) : t('ipBans.permanent') }}</template>
            <span v-else-if="column.key === 'actorOid'" :title="row.actorOid">{{ row.actorOid ? shortHash(row.actorOid) : t('ipBans.noActor') }}</span>
            <div v-else-if="column.key === 'id'" class="flex items-center justify-end gap-2">
              <RowMenu
                v-if="session.moderator && row.state === 'active'"
                :label="t('ipBans.columns.actions')"
                :items="[{ key: 'lift', label: t('ipBans.lift.open') }]"
                @select="lifting = row"
              />
            </div>
            <template v-else />
          </template>
        </DataTable>
        <EmptyState
          v-else
          data-testid="ip-bans-empty"
          :variant="showLifted ? 'no-matches' : 'no-items'"
          :title="showLifted ? t('ipBans.emptyLifted') : t('ipBans.empty')"
        />
      </template>
    </ResourceState>

    <IpBanCreateDialog :open="creating" @close="creating = false" @done="done" />
    <IpBanLiftDialog :open="lifting !== null" :ban="lifting" @close="lifting = null" @done="done" />

    <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
  </PageShell>
</template>
