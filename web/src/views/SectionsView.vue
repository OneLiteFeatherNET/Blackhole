<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../api/inject'
import type { Section } from '../api/types'
import DataTable, { type DataColumn } from '../components/DataTable.vue'
import PageShell from '../components/PageShell.vue'
import MdButton from '../components/md/MdButton.vue'
import MdDialog from '../components/md/MdDialog.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import ResourceState from '../components/ResourceState.vue'
import RowMenu, { type RowMenuItem } from '../components/RowMenu.vue'
import SectionEditor from '../components/sections/SectionEditor.vue'
import StatusPill from '../components/StatusPill.vue'
import { useResource } from '../composables/useResource'
import { formatDuration } from '../format'
import { useSessionStore } from '../stores/session'

const { t, locale } = useI18n()
const api = useApi()
const session = useSessionStore()

const sections = useResource(() => api.sections())

const editorOpen = ref(false)
const editing = ref<Section | null>(null)
const disabling = ref<Section | null>(null)
const disableError = ref('')
const disablingNow = ref(false)
const message = ref('')

const columns = computed<DataColumn<Section>[]>(() => [
  { key: 'key', label: t('sections.columns.section'), priority: 1 },
  { key: 'enforcement', label: t('sections.columns.enforcement'), priority: 2 },
  { key: 'servers', label: t('sections.columns.servers'), priority: 2 },
  { key: 'baseScore', label: t('sections.columns.scores'), priority: 3 },
  { key: 'permanentThreshold', label: t('sections.columns.thresholds'), priority: 2 },
  { key: 'enabled', label: t('sections.columns.actions'), priority: 1 },
])

// Edit is the row's one primary action; disabling is destructive and lives in the row menu.
const rowMenu = computed<RowMenuItem[]>(() => [{ key: 'disable', label: t('sections.disable.open'), destructive: true }])

function openEditor(section: Section | null): void {
  editing.value = section
  editorOpen.value = true
}

async function saved(done: string): Promise<void> {
  editorOpen.value = false
  message.value = done
  await sections.reload()
}

function askDisable(section: Section): void {
  disabling.value = section
  disableError.value = ''
}

function onRowMenu(section: Section, key: string): void {
  if (key === 'disable') askDisable(section)
}

async function confirmDisable(): Promise<void> {
  if (!disabling.value || disablingNow.value) return
  disablingNow.value = true
  const result = await api.disableSection(disabling.value.key)
  disablingNow.value = false
  if (!result.ok) {
    disableError.value = result.error.message
    return
  }
  disabling.value = null
  message.value = t('sections.disabled')
  await sections.reload()
}
</script>

<template>
  <PageShell :title="t('sections.title')" :description="t('sections.intro')">
    <template #actions>
      <MdButton v-if="session.administrator" data-testid="new-section" @click="openEditor(null)">{{ t('sections.new') }}</MdButton>
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
            <p class="text-label-large">{{ row.key }}</p>
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
            <MdButton variant="tonal" :data-testid="`edit-${row.key}`" @click="openEditor(row)">{{ t('sections.edit') }}</MdButton>
            <RowMenu v-if="row.enabled" :label="row.key" :items="rowMenu" @select="(key) => onRowMenu(row, key)" />
          </div>
          <template v-else>{{ row[column.key] }}</template>
        </template>
      </DataTable>
    </ResourceState>

    <SectionEditor :open="editorOpen" :section="editing" @close="editorOpen = false" @saved="saved" />

    <MdDialog :open="disabling !== null" :headline="t('sections.disable.title', { key: disabling?.key ?? '' })" @close="disabling = null">
      <p>{{ t('sections.disable.body') }}</p>
      <p v-if="disableError" data-testid="error" role="alert" class="mt-3 rounded-md-md bg-error-container p-3 text-on-error-container">
        {{ disableError }}
      </p>
      <template #actions>
        <MdButton variant="text" data-testid="cancel" @click="disabling = null">{{ t('actions.cancel') }}</MdButton>
        <MdButton variant="error" data-testid="confirm" :disabled="disablingNow" @click="confirmDisable">{{ t('sections.disable.confirm') }}</MdButton>
      </template>
    </MdDialog>
    <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
  </PageShell>
</template>
