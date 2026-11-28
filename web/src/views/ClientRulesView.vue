<script setup lang="ts">
// The client rule list. Creating opens its dialog at `/admin/client-rules/new`; each rule links to its own address.
// Its hits are a tab of that address (`?tab=hits`), its edit and delete actions are routes of it.
import { computed, provide, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useApi } from '../api/inject'
import type { ClientRule } from '../api/types'
import DataTable, { type DataColumn } from '../components/DataTable.vue'
import PageShell from '../components/PageShell.vue'
import ClientRuleEditRoute from '../components/clientrules/ClientRuleEditRoute.vue'
import { CONFIG_PAGE } from '../components/config/configPage'
import MdButton from '../components/md/MdButton.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import ResourceState from '../components/ResourceState.vue'
import RowMenu, { type RowMenuItem } from '../components/RowMenu.vue'
import StatusPill from '../components/StatusPill.vue'
import TextLink from '../components/TextLink.vue'
import { useResource } from '../composables/useResource'
import { useSessionStore } from '../stores/session'

const { t } = useI18n()
const api = useApi()
const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const rules = useResource(() => api.clientRules())
const message = ref('')

provide(CONFIG_PAGE, {
  reload: () => rules.reload(),
  async changed(done: string) {
    message.value = done
    await rules.reload()
  },
})

const creating = computed(() => route.name === 'admin-client-rule-new')

const columns = computed<DataColumn<ClientRule>[]>(() => [
  { key: 'key', label: t('clientRules.columns.rule'), priority: 1 },
  { key: 'kind', label: t('clientRules.columns.match'), priority: 2 },
  { key: 'scope', label: t('clientRules.columns.scope'), priority: 3 },
  { key: 'action', label: t('clientRules.columns.action'), priority: 2 },
  { key: 'hitCount', label: t('clientRules.columns.hits'), priority: 2 },
  { key: 'enabled', label: t('clientRules.columns.actions'), priority: 1 },
])

// Showing hits opens the hits tab of the rule's address for every reader.
const rowMenu = computed<RowMenuItem[]>(() => [{ key: 'hits', label: t('clientRules.showHits') }])

function matchText(rule: ClientRule): string {
  if (rule.kind === 'PROTOCOL') return `${rule.protocolMin ?? '?'} - ${rule.protocolMax ?? '?'}`
  return rule.patterns.join(', ')
}

function onRowMenu(rule: ClientRule, key: string): void {
  if (key === 'hits') void router.push({ name: 'admin-client-rule', params: { key: rule.key }, query: { tab: 'hits' } })
}
</script>

<template>
  <PageShell :title="t('clientRules.title')" :description="t('clientRules.intro')">
    <template #actions>
      <MdButton v-if="session.administrator" data-testid="new-client-rule" @click="router.push({ name: 'admin-client-rule-new' })">{{ t('clientRules.new') }}</MdButton>
    </template>

    <p role="note" data-testid="honesty" class="rounded-md-md bg-tertiary-container p-3 text-body-medium text-on-tertiary-container">
      {{ t('clientRules.honesty') }}
    </p>
    <p v-if="!session.administrator" data-testid="read-only" class="text-body-medium text-on-surface-variant">{{ t('clientRules.readOnly') }}</p>

    <ResourceState
      :loading="rules.loading.value"
      :error="rules.error.value"
      :has-data="rules.data.value !== null"
      @retry="rules.reload"
    >
      <template v-if="rules.data.value">
        <DataTable v-if="rules.data.value.length > 0" :caption="t('clientRules.title')" :columns="columns" :rows="rules.data.value">
          <template #cell="{ row, column }">
            <template v-if="column.key === 'key'">
              <TextLink :to="{ name: 'admin-client-rule', params: { key: row.key } }">{{ row.key }}</TextLink>
              <p class="text-body-small text-on-surface-variant">{{ row.displayName }}</p>
              <StatusPill v-if="!row.enabled" :label="t('clientRules.disabledPill')" />
            </template>
            <template v-else-if="column.key === 'kind'">
              <p class="text-body-small text-on-surface-variant">
                {{ t(`clientRules.kind.${row.kind}`) }}<span v-if="row.negate"> ({{ t('clientRules.negated') }})</span>
              </p>
              <p class="break-all text-body-medium">{{ matchText(row) }}</p>
              <StatusPill v-if="row.hideCommand" :label="t('clientRules.hidden')" />
            </template>
            <template v-else-if="column.key === 'scope'">
              <template v-if="row.scope === 'GLOBAL'">{{ t('clientRules.scope.GLOBAL') }}</template>
              <ul v-else class="flex flex-wrap gap-1">
                <li v-for="section in row.sectionKeys" :key="`s-${section}`"><StatusPill :label="section" tone="primary" /></li>
                <li v-for="server in row.servers" :key="`v-${server}`"><StatusPill :label="server" tone="primary" /></li>
              </ul>
            </template>
            <template v-else-if="column.key === 'action'">
              <StatusPill :label="t(`clientRules.action.${row.action}`)" :tone="row.action === 'NOTIFY' ? 'neutral' : 'error'" />
              <p v-if="row.templateKey" class="mt-1 text-body-small text-on-surface-variant">{{ row.templateKey }}</p>
              <p v-if="row.acknowledgedSpoofable" class="mt-1 text-body-small text-on-surface-variant" data-testid="acknowledged">
                {{ t('clientRules.acknowledged') }}
              </p>
            </template>
            <template v-else-if="column.key === 'hitCount'">{{ t('clientRules.hitCount', { n: row.hitCount ?? 0 }) }}</template>
            <div v-else-if="column.key === 'enabled'" class="flex items-center justify-end gap-1">
              <MdButton
                v-if="session.administrator"
                variant="tonal"
                :data-testid="`edit-${row.key}`"
                @click="router.push({ name: 'admin-client-rule-edit', params: { key: row.key } })"
              >
                {{ t('clientRules.edit') }}
              </MdButton>
              <RowMenu :label="row.key" :items="rowMenu" @select="(key) => onRowMenu(row, key)" />
            </div>
            <template v-else />
          </template>
        </DataTable>
        <p v-else data-testid="empty" class="text-body-large text-on-surface-variant">{{ t('clientRules.empty') }}</p>
      </template>
    </ResourceState>

    <ClientRuleEditRoute v-if="creating" />
    <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
  </PageShell>
</template>
