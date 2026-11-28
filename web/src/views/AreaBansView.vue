<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute } from 'vue-router'
import { useApi } from '../api/inject'
import type { AreaBan } from '../api/types'
import DataTable, { type DataColumn } from '../components/DataTable.vue'
import PageShell from '../components/PageShell.vue'
import AreaBanCreateDialog from '../components/areabans/AreaBanCreateDialog.vue'
import MdButton from '../components/md/MdButton.vue'
import MdCheckbox from '../components/md/MdCheckbox.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import ResourceState from '../components/ResourceState.vue'
import StatusPill from '../components/StatusPill.vue'
import { useResource } from '../composables/useResource'
import { formatDateTime } from '../format'
import { useRoutedDialog } from '../router/routedDialog'
import { useQueryParam } from '../router/queryParam'
import { useSessionStore } from '../stores/session'

// Area bans of ASNs and countries. Everyone with access reads them; moderators and admins create and lift them.
// `?status=lifted` adds the lifted and expired bans to the list.
const { t, locale } = useI18n()
const api = useApi()
const session = useSessionStore()
const route = useRoute()

const status = useQueryParam('status', ['active', 'lifted'] as const, 'active')
const includeInactive = computed({
  get: () => status.value === 'lifted',
  set: (next: boolean) => (status.value = next ? 'lifted' : 'active'),
})
const bans = useResource(() => api.areaBans(includeInactive.value))
watch(includeInactive, () => void bans.reload())

// The create form is the child route `new`; the list stays rendered behind it.
const create = useRoutedDialog({ parent: 'area-bans', child: 'area-ban-new' })
const message = ref('')

type Status = 'active' | 'expired' | 'lifted'
type AreaBanRow = AreaBan & { status: Status; actions: '' }

function statusOf(ban: AreaBan): Status {
  if (ban.liftedAt !== undefined && ban.liftedAt !== null) return 'lifted'
  return ban.active ? 'active' : 'expired'
}

const rows = computed<AreaBanRow[]>(() => (bans.data.value ?? []).map((ban) => ({ ...ban, status: statusOf(ban), actions: '' })))

const columns = computed<DataColumn<AreaBanRow>[]>(() => [
  { key: 'value', label: t('areaBans.columns.value'), priority: 1 },
  { key: 'reason', label: t('areaBans.columns.reason'), priority: 2 },
  { key: 'source', label: t('areaBans.columns.source'), priority: 3 },
  { key: 'expiresAt', label: t('areaBans.columns.expires'), priority: 2 },
  { key: 'status', label: t('areaBans.columns.status'), priority: 1 },
  { key: 'actions', label: t('areaBans.columns.actions'), priority: 1 },
])

function expiryText(ban: AreaBan): string {
  if (ban.expiresAt !== undefined && ban.expiresAt !== null) return formatDateTime(ban.expiresAt, locale.value)
  return ban.active ? t('areaBans.permanent') : ''
}

async function done(text: string): Promise<void> {
  message.value = text
  await bans.reload()
}

async function created(): Promise<void> {
  await create.close()
  await done(t('areaBans.created'))
}
</script>

<template>
  <PageShell :title="t('areaBans.title')" :description="t('areaBans.intro')">
    <template #actions>
      <MdButton v-if="session.moderator" as="link" data-testid="new-area-ban" :to="{ name: 'area-ban-new', query: route.query }">
        {{ t('areaBans.create.open') }}
      </MdButton>
    </template>

    <MdCheckbox
      v-model="includeInactive"
      data-testid="show-inactive"
      :label="t('areaBans.showInactive')"
      :supporting-text="t('areaBans.showInactiveHint')"
    />

    <ResourceState :loading="bans.loading.value" :error="bans.error.value" :has-data="bans.data.value !== null" @retry="bans.reload">
      <template v-if="bans.data.value">
        <DataTable v-if="rows.length > 0" :caption="t('areaBans.title')" :columns="columns" :rows="rows">
          <template #cell="{ row, column }">
            <template v-if="column.key === 'value'">
              <p class="text-label-large">{{ t(`areaBans.kind.${row.kind}`) }}</p>
              <RouterLink
                :to="{ name: 'area-ban', params: { id: row.id } }"
                class="break-all text-body-medium text-primary underline underline-offset-2 focus-ring"
              >
                {{ row.value }}
              </RouterLink>
            </template>
            <template v-else-if="column.key === 'expiresAt'">{{ expiryText(row) }}</template>
            <template v-else-if="column.key === 'status'">
              <StatusPill
                :label="t(`areaBans.status.${row.status}`)"
                :tone="row.status === 'active' ? 'error' : 'neutral'"
              />
              <p v-if="row.status === 'lifted' && row.liftedAt" class="mt-1 text-body-small text-on-surface-variant">
                {{ t('areaBans.liftedAt', { when: formatDateTime(row.liftedAt, locale) }) }}
              </p>
            </template>
            <MdButton
              v-else-if="column.key === 'actions' && session.moderator && row.status === 'active'"
              as="link"
              variant="text"
              :data-testid="`lift-${row.id}`"
              :to="{ name: 'area-ban-lift', params: { id: row.id } }"
            >
              {{ t('areaBans.lift.open') }}
            </MdButton>
            <template v-else-if="column.key !== 'actions'">{{ row[column.key] }}</template>
          </template>
        </DataTable>
        <p v-else data-testid="empty" class="text-body-large text-on-surface-variant">{{ t('areaBans.empty') }}</p>
      </template>
    </ResourceState>

    <AreaBanCreateDialog :open="create.open.value" @close="create.close" @created="created" />

    <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
  </PageShell>
</template>
