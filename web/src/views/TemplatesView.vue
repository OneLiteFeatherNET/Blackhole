<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../api/inject'
import type { Template } from '../api/types'
import DataTable, { type DataColumn } from '../components/DataTable.vue'
import EmptyState from '../components/EmptyState.vue'
import PageShell from '../components/PageShell.vue'
import MdButton from '../components/md/MdButton.vue'
import MdDialog from '../components/md/MdDialog.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import ResourceState from '../components/ResourceState.vue'
import RowMenu, { type RowMenuItem } from '../components/RowMenu.vue'
import StatusPill from '../components/StatusPill.vue'
import TemplateEditor from '../components/templates/TemplateEditor.vue'
import { useResource } from '../composables/useResource'
import { formatDuration } from '../format'
import { useSessionStore } from '../stores/session'

const { t, locale } = useI18n()
const api = useApi()
const session = useSessionStore()

const templates = useResource(() => api.templates())
const sections = useResource(() => api.sections())

const editorOpen = ref(false)
const editing = ref<Template | null>(null)
const deleting = ref<Template | null>(null)
const deleteError = ref('')
const deletingNow = ref(false)
const message = ref('')

const columns = computed<DataColumn<Template>[]>(() => [
  { key: 'key', label: t('templates.columns.key'), priority: 1 },
  { key: 'reason', label: t('templates.columns.reason'), priority: 2 },
  { key: 'section', label: t('templates.columns.section'), priority: 2 },
  { key: 'escalationGroup', label: t('templates.columns.group'), priority: 3 },
  { key: 'steps', label: t('templates.columns.steps'), priority: 1 },
  { key: 'identifier', label: t('templates.columns.actions'), priority: 1 },
])

// Edit is the row's one primary action; delete is destructive and lives in the row menu.
const rowMenu = computed<RowMenuItem[]>(() => [{ key: 'delete', label: t('templates.delete.open'), destructive: true }])

function openEditor(template: Template | null): void {
  editing.value = template
  editorOpen.value = true
}

async function saved(done: string): Promise<void> {
  editorOpen.value = false
  message.value = done
  await templates.reload()
}

function askDelete(template: Template): void {
  deleting.value = template
  deleteError.value = ''
}

async function confirmDelete(): Promise<void> {
  if (!deleting.value || deletingNow.value) return
  deletingNow.value = true
  const result = await api.deleteTemplate(deleting.value.identifier)
  deletingNow.value = false
  if (!result.ok) {
    deleteError.value =
      result.error.kind === 'CONFLICT' ? `${t('templates.delete.inUse')} ${result.error.message}` : result.error.message
    return
  }
  deleting.value = null
  message.value = t('templates.deleted')
  await templates.reload()
}

function onRowMenu(template: Template, key: string): void {
  if (key === 'delete') askDelete(template)
}
</script>

<template>
  <PageShell :title="t('templates.title')" :description="t('templates.intro')">
    <template #actions>
      <MdButton v-if="session.administrator" data-testid="new-template" @click="openEditor(null)">{{ t('templates.new') }}</MdButton>
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
            <template v-if="column.key === 'section'"><StatusPill :label="row.section" tone="primary" /></template>
            <template v-else-if="column.key === 'escalationGroup'">
              {{ row.escalationGroup }}<span v-if="row.escalationWindow" class="text-on-surface-variant"> ({{ formatDuration(row.escalationWindow, locale) }})</span>
            </template>
            <template v-else-if="column.key === 'steps'">
              <p class="text-label-large">{{ t('templates.stepCount', { count: row.steps.length }, row.steps.length) }}</p>
              <ol class="mt-1 flex flex-col gap-1 text-body-small text-on-surface-variant">
                <li v-for="(step, index) in row.steps" :key="index">
                  {{ t('templates.stepLine', { n: index + 1, duration: formatDuration(step.duration ?? '', locale), cost: step.eloCost }) }}
                </li>
              </ol>
            </template>
            <div v-else-if="column.key === 'identifier' && session.administrator" class="flex items-center justify-end gap-1">
              <MdButton variant="tonal" :data-testid="`edit-${row.key}`" @click="openEditor(row)">{{ t('templates.edit') }}</MdButton>
              <RowMenu :label="row.key" :items="rowMenu" @select="(key) => onRowMenu(row, key)" />
            </div>
            <template v-else>{{ row[column.key] }}</template>
          </template>
        </DataTable>

        <EmptyState v-else variant="no-items" :title="t('templates.empty')" />
      </template>
    </ResourceState>

    <TemplateEditor
      :open="editorOpen"
      :template="editing"
      :sections="sections.data.value ?? []"
      @close="editorOpen = false"
      @saved="saved"
    />

    <MdDialog :open="deleting !== null" :headline="t('templates.delete.title', { key: deleting?.key ?? '' })" @close="deleting = null">
      <p>{{ t('templates.delete.body') }}</p>
      <p v-if="deleteError" data-testid="error" role="alert" class="mt-3 rounded-md-md bg-error-container p-3 text-on-error-container">
        {{ deleteError }}
      </p>
      <template #actions>
        <MdButton variant="text" data-testid="cancel" @click="deleting = null">{{ t('actions.cancel') }}</MdButton>
        <MdButton variant="error" data-testid="confirm" :disabled="deletingNow" @click="confirmDelete">{{ t('templates.delete.confirm') }}</MdButton>
      </template>
    </MdDialog>
    <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
  </PageShell>
</template>
