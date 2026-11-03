<script setup lang="ts">
// The section list. Creating opens its dialog at `/admin/sections/new`; each row links to its own address, and its
// edit and disable actions are routes of that address (`/admin/sections/:key/edit`, `/disable`).
import { computed, provide, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useApi } from '../api/inject'
import type { Section } from '../api/types'
import DataTable, { type DataColumn } from '../components/DataTable.vue'
import PageShell from '../components/PageShell.vue'
import { CONFIG_PAGE } from '../components/config/configPage'
import MdButton from '../components/md/MdButton.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import ResourceState from '../components/ResourceState.vue'
import RowMenu, { type RowMenuItem } from '../components/RowMenu.vue'
import SectionEditRoute from '../components/sections/SectionEditRoute.vue'
import StatusPill from '../components/StatusPill.vue'
import TextLink from '../components/TextLink.vue'
import { useResource } from '../composables/useResource'
import { formatDuration } from '../format'
import { useSessionStore } from '../stores/session'

const { t, locale } = useI18n()
const api = useApi()
const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const sections = useResource(() => api.sections())
const message = ref('')

provide(CONFIG_PAGE, {
  reload: () => sections.reload(),
  async changed(done: string) {
    message.value = done
    await sections.reload()
  },
})

const creating = computed(() => route.name === 'admin-section-new')

const columns = computed<DataColumn<Section>[]>(() => [
  { key: 'key', label: t('sections.columns.section'), priority: 1 },
  { key: 'enforcement', label: t('sections.columns.enforcement'), priority: 2 },
  { key: 'servers', label: t('sections.columns.servers'), priority: 2 },
  { key: 'baseScore', label: t('sections.columns.scores'), priority: 3 },
  { key: 'permanentThreshold', label: t('sections.columns.thresholds'), priority: 2 },
  { key: 'enabled', label: t('sections.columns.actions'), priority: 1 },
])

// Disabling is destructive and lives in the row menu; the menu only opens the route of the disable confirmation.
const rowMenu = computed<RowMenuItem[]>(() => [{ key: 'disable', label: t('sections.disable.open'), destructive: true }])

function onRowMenu(section: Section, key: string): void {
  if (key === 'disable') void router.push({ name: 'admin-section-disable', params: { key: section.key } })
}
</script>

<template>
  <PageShell :title="t('sections.title')" :description="t('sections.intro')">
    <template #actions>
      <MdButton v-if="session.administrator" data-testid="new-section" @click="router.push({ name: 'admin-section-new' })">{{ t('sections.new') }}</MdButton>
    </template>

    <ResourceState
      :loading="sections.loading.value"
      :error="sections.error.value"
      :has-data="sections.data.value !== null"
      @retry="sections.reload"
    >
      <DataTable v-if="sections.data.value" :caption="t('sections.title')" :columns="columns" :rows="sections.data.value">
        <template #cell="{ row, column }">
          <template v-if="column.key === 'key'">
            <TextLink :to="{ name: 'admin-section', params: { key: row.key } }">{{ row.key }}</TextLink>
            <p class="text-body-small text-on-surface-variant">{{ row.displayName }}</p>
            <StatusPill v-if="!row.enabled" :label="t('sections.disabledPill')" />
          </template>
          <template v-else-if="column.key === 'enforcement'">{{ t(`enforcement.${row.enforcement}`) }}</template>
          <template v-else-if="column.key === 'servers'">
            <template v-if="row.servers.length">
              <p class="mb-1 text-body-small text-on-surface-variant" data-testid="target-kind">{{ t(`sections.targetKind.${row.targetKind ?? 'PATTERN'}`) }}</p>
              <ul class="flex flex-wrap gap-1">
                <li v-for="pattern in row.servers" :key="pattern"><StatusPill :label="pattern" tone="primary" /></li>
              </ul>
            </template>
            <span v-else class="text-on-surface-variant" aria-hidden="true">-</span>
          </template>
          <template v-else-if="column.key === 'baseScore'">
            {{ t('sections.base') }} {{ row.baseScore }} · {{ t('sections.pardon') }} {{ row.pardonScore }} · {{ t('sections.decay') }} {{ row.decayPerDay }}
          </template>
          <template v-else-if="column.key === 'permanentThreshold'">
            {{ t('sections.soft') }}
            <template v-if="row.softThreshold !== null && row.softThreshold !== undefined">
              {{ row.softThreshold }}<span v-if="row.softDuration"> ({{ formatDuration(row.softDuration, locale) }})</span>
            </template>
            <template v-else>-</template>
            · {{ t('sections.permanent') }} {{ row.permanentThreshold }}
          </template>
          <div v-else-if="column.key === 'enabled' && session.administrator" class="flex items-center justify-end gap-1">
            <MdButton variant="tonal" :data-testid="`edit-${row.key}`" @click="router.push({ name: 'admin-section-edit', params: { key: row.key } })">
              {{ t('sections.edit') }}
            </MdButton>
            <RowMenu v-if="row.enabled" :label="row.key" :items="rowMenu" @select="(key) => onRowMenu(row, key)" />
          </div>
          <template v-else>{{ row[column.key] }}</template>
        </template>
      </DataTable>
    </ResourceState>

    <SectionEditRoute v-if="creating" />
    <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
  </PageShell>
</template>
