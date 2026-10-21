<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../api/inject'
import type { Template } from '../api/types'
import MdButton from '../components/md/MdButton.vue'
import MdDataTable from '../components/md/MdDataTable.vue'
import MdDialog from '../components/md/MdDialog.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import ResourceState from '../components/ResourceState.vue'
import StatusPill from '../components/StatusPill.vue'
import TemplateEditor from '../components/templates/TemplateEditor.vue'
import { useResource } from '../composables/useResource'
import { formatDuration } from '../format'

const { t, locale } = useI18n()
const api = useApi()

const templates = useResource(() => api.templates())
const sections = useResource(() => api.sections())

const editorOpen = ref(false)
const editing = ref<Template | null>(null)
const deleting = ref<Template | null>(null)
const deleteError = ref('')
const deletingNow = ref(false)
const message = ref('')

const columns = computed(() => [
  { key: 'key' as const, label: t('templates.columns.key') },
  { key: 'reason' as const, label: t('templates.columns.reason') },
  { key: 'section' as const, label: t('templates.columns.section') },
  { key: 'escalationGroup' as const, label: t('templates.columns.group') },
  { key: 'steps' as const, label: t('templates.columns.steps') },
  { key: 'identifier' as const, label: t('templates.columns.actions') },
])

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
</script>

<template>
  <ResourceState
    :loading="templates.loading.value"
    :error="templates.error.value"
    :has-data="templates.data.value !== null"
    @retry="templates.reload"
  >
    <div class="flex flex-col gap-4">
      <div class="flex flex-wrap items-center gap-3">
        <p class="min-w-0 flex-1 text-body-large text-on-surface-variant">{{ t('templates.intro') }}</p>
        <MdButton data-testid="new-template" @click="openEditor(null)">{{ t('templates.new') }}</MdButton>
      </div>

      <p v-if="templates.data.value?.length === 0" class="text-body-large text-on-surface-variant">{{ t('templates.empty') }}</p>
      <MdDataTable v-else-if="templates.data.value" :label="t('templates.title')" :columns="columns" :rows="templates.data.value">
        <template #cell="{ row, column }">
          <template v-if="column.key === 'section'"><StatusPill :label="row.section" tone="primary" /></template>
          <template v-else-if="column.key === 'escalationGroup'">
            {{ row.escalationGroup }}<span v-if="row.escalationWindow" class="text-on-surface-variant"> ({{ formatDuration(row.escalationWindow, locale) }})</span>
          </template>
          <template v-else-if="column.key === 'steps'">
            <p class="text-label-large">{{ t('templates.stepCount', { count: row.steps.length }, row.steps.length) }}</p>
            <ol class="mt-1 flex flex-col gap-0.5 text-body-small text-on-surface-variant">
              <li v-for="(step, index) in row.steps" :key="index">
                {{ index + 1 }}. {{ formatDuration(step.duration, locale) }} / {{ step.eloCost }}
              </li>
            </ol>
          </template>
          <div v-else-if="column.key === 'identifier'" class="flex gap-1">
            <MdButton variant="text" :data-testid="`edit-${row.key}`" @click="openEditor(row)">{{ t('templates.edit') }}</MdButton>
            <MdButton variant="text" :data-testid="`delete-${row.key}`" @click="askDelete(row)">{{ t('templates.delete.open') }}</MdButton>
          </div>
          <template v-else>{{ row[column.key] }}</template>
        </template>
      </MdDataTable>
    </div>
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
      <MdButton data-testid="confirm" :disabled="deletingNow" @click="confirmDelete">{{ t('templates.delete.confirm') }}</MdButton>
    </template>
  </MdDialog>
  <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
</template>
