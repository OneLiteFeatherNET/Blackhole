<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../api/inject'
import type { Section } from '../api/types'
import MdButton from '../components/md/MdButton.vue'
import MdDataTable from '../components/md/MdDataTable.vue'
import MdDialog from '../components/md/MdDialog.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import ResourceState from '../components/ResourceState.vue'
import SectionEditor from '../components/sections/SectionEditor.vue'
import StatusPill from '../components/StatusPill.vue'
import { useResource } from '../composables/useResource'
import { formatDuration } from '../format'

const { t, locale } = useI18n()
const api = useApi()

const sections = useResource(() => api.sections())

const editorOpen = ref(false)
const editing = ref<Section | null>(null)
const disabling = ref<Section | null>(null)
const disableError = ref('')
const disablingNow = ref(false)
const message = ref('')

const columns = computed(() => [
  { key: 'key' as const, label: t('sections.columns.section') },
  { key: 'enforcement' as const, label: t('sections.columns.enforcement') },
  { key: 'servers' as const, label: t('sections.columns.servers') },
  { key: 'baseScore' as const, label: t('sections.columns.scores') },
  { key: 'permanentThreshold' as const, label: t('sections.columns.thresholds') },
  { key: 'enabled' as const, label: t('sections.columns.actions') },
])

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
  <ResourceState
    :loading="sections.loading.value"
    :error="sections.error.value"
    :has-data="sections.data.value !== null"
    @retry="sections.reload"
  >
    <div class="flex flex-col gap-4">
      <div class="flex flex-wrap items-center gap-3">
        <p class="min-w-0 flex-1 text-body-large text-on-surface-variant">{{ t('sections.intro') }}</p>
        <MdButton data-testid="new-section" @click="openEditor(null)">{{ t('sections.new') }}</MdButton>
      </div>

      <MdDataTable v-if="sections.data.value" :label="t('sections.title')" :columns="columns" :rows="sections.data.value">
        <template #cell="{ row, column }">
          <template v-if="column.key === 'key'">
            <p class="text-label-large">{{ row.key }}</p>
            <p class="text-body-small text-on-surface-variant">{{ row.displayName }}</p>
            <StatusPill v-if="!row.enabled" :label="t('sections.disabledPill')" />
          </template>
          <template v-else-if="column.key === 'enforcement'">{{ t(`enforcement.${row.enforcement}`) }}</template>
          <template v-else-if="column.key === 'servers'">
            <ul v-if="row.servers.length" class="flex flex-wrap gap-1">
              <li v-for="pattern in row.servers" :key="pattern"><StatusPill :label="pattern" tone="primary" /></li>
            </ul>
            <span v-else class="text-on-surface-variant" aria-hidden="true">-</span>
          </template>
          <dl v-else-if="column.key === 'baseScore'" class="grid grid-cols-[auto_1fr] gap-x-3 text-body-small">
            <dt class="text-on-surface-variant">{{ t('sections.base') }}</dt>
            <dd>{{ row.baseScore }}</dd>
            <dt class="text-on-surface-variant">{{ t('sections.pardon') }}</dt>
            <dd>{{ row.pardonScore }}</dd>
            <dt class="text-on-surface-variant">{{ t('sections.decay') }}</dt>
            <dd>{{ row.decayPerDay }}</dd>
          </dl>
          <dl v-else-if="column.key === 'permanentThreshold'" class="grid grid-cols-[auto_1fr] gap-x-3 text-body-small">
            <dt class="text-on-surface-variant">{{ t('sections.soft') }}</dt>
            <dd>
              <template v-if="row.softThreshold !== null && row.softThreshold !== undefined">
                {{ row.softThreshold }}<span v-if="row.softDuration"> ({{ formatDuration(row.softDuration, locale) }})</span>
              </template>
              <template v-else>-</template>
            </dd>
            <dt class="text-on-surface-variant">{{ t('sections.permanent') }}</dt>
            <dd>{{ row.permanentThreshold }}</dd>
          </dl>
          <div v-else-if="column.key === 'enabled'" class="flex gap-1">
            <MdButton variant="text" :data-testid="`edit-${row.key}`" @click="openEditor(row)">{{ t('sections.edit') }}</MdButton>
            <MdButton v-if="row.enabled" variant="text" :data-testid="`disable-${row.key}`" @click="askDisable(row)">
              {{ t('sections.disable.open') }}
            </MdButton>
          </div>
          <template v-else>{{ row[column.key] }}</template>
        </template>
      </MdDataTable>
    </div>
  </ResourceState>

  <SectionEditor :open="editorOpen" :section="editing" @close="editorOpen = false" @saved="saved" />

  <MdDialog :open="disabling !== null" :headline="t('sections.disable.title', { key: disabling?.key ?? '' })" @close="disabling = null">
    <p>{{ t('sections.disable.body') }}</p>
    <p v-if="disableError" data-testid="error" role="alert" class="mt-3 rounded-md-md bg-error-container p-3 text-on-error-container">
      {{ disableError }}
    </p>
    <template #actions>
      <MdButton variant="text" data-testid="cancel" @click="disabling = null">{{ t('actions.cancel') }}</MdButton>
      <MdButton data-testid="confirm" :disabled="disablingNow" @click="confirmDisable">{{ t('sections.disable.confirm') }}</MdButton>
    </template>
  </MdDialog>
  <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
</template>
