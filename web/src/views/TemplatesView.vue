<script setup lang="ts">
// The template list. Creating opens its dialog at `/admin/templates/new`; each row links to its own address, and
// its edit and delete actions are routes of that address (`/admin/templates/:id/edit`).
import { computed, provide, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useApi } from '../api/inject'
import type { Template } from '../api/types'
import DataTable, { type DataColumn } from '../components/DataTable.vue'
import EmptyState from '../components/EmptyState.vue'
import PageShell from '../components/PageShell.vue'
import { CONFIG_PAGE } from '../components/config/configPage'
import MdButton from '../components/md/MdButton.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import ResourceState from '../components/ResourceState.vue'
import StatusPill from '../components/StatusPill.vue'
import TemplateEditRoute from '../components/templates/TemplateEditRoute.vue'
import TextLink from '../components/TextLink.vue'
import { useResource } from '../composables/useResource'
import { formatDuration } from '../format'
import { useSessionStore } from '../stores/session'

const { t, locale } = useI18n()
const api = useApi()
const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const templates = useResource(() => api.templates())
const message = ref('')

provide(CONFIG_PAGE, {
  reload: () => templates.reload(),
  async changed(done: string) {
    message.value = done
    await templates.reload()
  },
})

const creating = computed(() => route.name === 'admin-template-new')

const columns = computed<DataColumn<Template>[]>(() => [
  { key: 'key', label: t('templates.columns.key'), priority: 1 },
  { key: 'reason', label: t('templates.columns.reason'), priority: 2 },
  { key: 'section', label: t('templates.columns.section'), priority: 2 },
  { key: 'escalationGroup', label: t('templates.columns.group'), priority: 3 },
  { key: 'steps', label: t('templates.columns.steps'), priority: 1 },
  { key: 'identifier', label: t('templates.columns.actions'), priority: 1 },
])
</script>

<template>
  <PageShell :title="t('templates.title')" :description="t('templates.intro')">
    <template #actions>
      <MdButton v-if="session.administrator" data-testid="new-template" @click="router.push({ name: 'admin-template-new' })">{{ t('templates.new') }}</MdButton>
    </template>

    <ResourceState
      :loading="templates.loading.value"
      :error="templates.error.value"
      :has-data="templates.data.value !== null"
      @retry="templates.reload"
    >
      <template v-if="templates.data.value">
        <DataTable v-if="templates.data.value.length > 0" :caption="t('templates.title')" :columns="columns" :rows="templates.data.value">
          <template #cell="{ row, column }">
            <template v-if="column.key === 'key'">
              <TextLink :to="{ name: 'admin-template', params: { id: row.identifier } }">{{ row.key }}</TextLink>
            </template>
            <template v-else-if="column.key === 'section'"><StatusPill :label="row.section" tone="primary" /></template>
            <template v-else-if="column.key === 'escalationGroup'">
              {{ row.escalationGroup }}<span v-if="row.escalationWindow" class="text-on-surface-variant"> ({{ formatDuration(row.escalationWindow, locale) }})</span>
            </template>
            <template v-else-if="column.key === 'steps'">
              <p class="text-label-large">{{ t('templates.stepCount', { count: row.steps.length }, row.steps.length) }}</p>
              <ol class="mt-1 flex flex-col gap-1 text-body-small text-on-surface-variant">
                <li v-for="(step, index) in row.steps" :key="index">
                  {{
                    t('templates.stepLine', {
                      n: index + 1,
                      duration: step.kind && step.kind !== 'PUNISH' ? t(`templates.editor.kinds.${step.kind}`) : formatDuration(step.duration ?? '', locale),
                      cost: step.eloCost,
                    })
                  }}
                </li>
              </ol>
            </template>
            <div v-else-if="column.key === 'identifier' && session.administrator" class="flex items-center justify-end gap-1">
              <MdButton
                variant="tonal"
                :data-testid="`edit-${row.key}`"
                @click="router.push({ name: 'admin-template-edit', params: { id: row.identifier } })"
              >
                {{ t('templates.edit') }}
              </MdButton>
            </div>
            <template v-else>{{ row[column.key] }}</template>
          </template>
        </DataTable>

        <EmptyState v-else variant="no-items" :title="t('templates.empty')" />
      </template>
    </ResourceState>

    <TemplateEditRoute v-if="creating" />
    <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
  </PageShell>
</template>
