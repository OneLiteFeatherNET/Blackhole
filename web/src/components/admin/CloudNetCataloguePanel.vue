<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { useApi } from '../../api/inject'
import { useResource } from '../../composables/useResource'
import { formatDateTime } from '../../format'
import { useQueryChoice } from '../../router/queryChoice'
import MdButton from '../md/MdButton.vue'
import MdDataTable from '../md/MdDataTable.vue'
import ResourceState from '../ResourceState.vue'
import StatusPill from '../StatusPill.vue'

// Read-only view of what the proxies report from CloudNet (GET /cloudnet/catalogue). `?tab=` picks the tasks, groups and
// services, or the proxies. Every entry links to its own address, `/admin/cloudnet/:kind/:key`.
const TABS = ['catalogue', 'proxies'] as const

const { t, locale } = useI18n()
const api = useApi()
const catalogue = useResource(() => api.cloudnetCatalogue())
const tab = useQueryChoice('tab', TABS, 'catalogue')

const taskColumns = computed(() => [
  { key: 'name' as const, label: t('cloudnetCatalogue.columns.task') },
  { key: 'groups' as const, label: t('cloudnetCatalogue.columns.groups') },
])
const serviceColumns = computed(() => [
  { key: 'name' as const, label: t('cloudnetCatalogue.columns.service') },
  { key: 'task' as const, label: t('cloudnetCatalogue.columns.task') },
  { key: 'running' as const, label: t('cloudnetCatalogue.columns.state') },
])

const reportedAt = computed(() => {
  const value = catalogue.data.value?.reportedAt
  const millis = value ? Date.parse(value) : Number.NaN
  return Number.isNaN(millis) ? null : formatDateTime(millis, locale.value)
})
</script>

<template>
  <section aria-labelledby="cloudnet-title" class="flex flex-col gap-4" data-testid="cloudnet-catalogue">
    <div class="flex flex-wrap items-center justify-between gap-2">
      <h2 id="cloudnet-title" class="text-title-medium">{{ t('cloudnetCatalogue.title') }}</h2>
      <MdButton variant="outlined" data-testid="reload" :disabled="catalogue.loading.value" @click="catalogue.reload">{{ t('cloudnetCatalogue.reload') }}</MdButton>
    </div>
    <p class="text-body-large text-on-surface-variant">{{ t('cloudnetCatalogue.intro') }}</p>
    <nav :aria-label="t('cloudnetCatalogue.tabsLabel')" class="flex flex-wrap gap-2">
      <MdButton
        v-for="value in TABS"
        :key="value"
        as="link"
        :variant="tab === value ? 'filled' : 'outlined'"
        :to="{ name: 'admin-cloudnet', query: { tab: value } }"
        :aria-current="tab === value ? 'page' : undefined"
        :data-testid="`cloudnet-tab-${value}`"
      >
        {{ t(`cloudnetCatalogue.tabs.${value}`) }}
      </MdButton>
    </nav>
    <ResourceState :loading="catalogue.loading.value" :error="catalogue.error.value" :has-data="catalogue.data.value !== null" @retry="catalogue.reload">
      <p v-if="catalogue.data.value && !catalogue.data.value.available" data-testid="cloudnet-empty">{{ t('cloudnetCatalogue.empty') }}</p>
      <div v-else-if="catalogue.data.value" class="flex flex-col gap-6">
        <p data-testid="freshness" class="text-body-medium">
          <template v-if="reportedAt">{{ t('cloudnetCatalogue.reportedAt', { time: reportedAt }) }}</template>
          <template v-else>{{ t('cloudnetCatalogue.reportedUnknown') }}</template>
        </p>

        <div v-if="tab === 'proxies'">
          <h3 class="mb-2 text-title-medium">{{ t('cloudnetCatalogue.proxies') }}</h3>
          <p v-if="catalogue.data.value.proxies.length === 0" class="text-body-medium text-on-surface-variant">{{ t('cloudnetCatalogue.noProxies') }}</p>
          <ul v-else class="flex flex-wrap gap-2" data-testid="proxies">
            <li v-for="proxy in catalogue.data.value.proxies" :key="proxy">
              <RouterLink :to="{ name: 'admin-cloudnet-entry', params: { kind: 'proxies', key: proxy } }" class="focus-ring">
                <StatusPill :label="proxy" />
              </RouterLink>
            </li>
          </ul>
        </div>

        <template v-else>
          <div>
            <h3 class="mb-2 text-title-medium">{{ t('cloudnetCatalogue.tasks') }}</h3>
            <p v-if="catalogue.data.value.tasks.length === 0" class="text-body-medium text-on-surface-variant">{{ t('cloudnetCatalogue.noneTasks') }}</p>
            <MdDataTable v-else :label="t('cloudnetCatalogue.tasks')" :columns="taskColumns" :rows="catalogue.data.value.tasks" data-testid="tasks">
              <template #cell="{ row, column }">
                <template v-if="column.key === 'groups'">{{ (row.groups ?? []).join(', ') || t('cloudnetCatalogue.none') }}</template>
                <RouterLink v-else :to="{ name: 'admin-cloudnet-entry', params: { kind: 'tasks', key: row.name } }" class="text-primary underline underline-offset-2 focus-ring">
                  {{ row.name }}
                </RouterLink>
              </template>
            </MdDataTable>
          </div>

          <div>
            <h3 class="mb-2 text-title-medium">{{ t('cloudnetCatalogue.groups') }}</h3>
            <p v-if="catalogue.data.value.groups.length === 0" class="text-body-medium text-on-surface-variant">{{ t('cloudnetCatalogue.noneGroups') }}</p>
            <ul v-else class="flex flex-wrap gap-2" data-testid="groups">
              <li v-for="group in catalogue.data.value.groups" :key="group">
                <RouterLink :to="{ name: 'admin-cloudnet-entry', params: { kind: 'groups', key: group } }" class="focus-ring">
                  <StatusPill :label="group" />
                </RouterLink>
              </li>
            </ul>
          </div>

          <div>
            <h3 class="mb-2 text-title-medium">{{ t('cloudnetCatalogue.services') }}</h3>
            <p v-if="catalogue.data.value.services.length === 0" class="text-body-medium text-on-surface-variant">{{ t('cloudnetCatalogue.noneServices') }}</p>
            <MdDataTable v-else :label="t('cloudnetCatalogue.services')" :columns="serviceColumns" :rows="catalogue.data.value.services" data-testid="services">
              <template #cell="{ row, column }">
                <template v-if="column.key === 'task'">{{ row.task || t('cloudnetCatalogue.none') }}</template>
                <StatusPill
                  v-else-if="column.key === 'running'"
                  :label="row.running ? t('cloudnetCatalogue.running') : t('cloudnetCatalogue.stopped')"
                  :tone="row.running ? 'primary' : 'neutral'"
                />
                <RouterLink v-else :to="{ name: 'admin-cloudnet-entry', params: { kind: 'services', key: row.name } }" class="text-primary underline underline-offset-2 focus-ring">
                  {{ row.name }}
                </RouterLink>
              </template>
            </MdDataTable>
          </div>
        </template>
      </div>
    </ResourceState>
  </section>
</template>
