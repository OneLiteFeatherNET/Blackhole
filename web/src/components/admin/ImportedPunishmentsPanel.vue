<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { ImportedPunishmentGroup } from '../../api/types'
import { useResource } from '../../composables/useResource'
import MdButton from '../md/MdButton.vue'
import MdDataTable from '../md/MdDataTable.vue'
import MdDialog from '../md/MdDialog.vue'
import MdSelect from '../md/MdSelect.vue'
import MdSnackbar from '../md/MdSnackbar.vue'
import ResourceState from '../ResourceState.vue'
import { useImportTemplateOptions } from './importTemplates'

const { t } = useI18n()
const api = useApi()

const groups = useResource(() => api.importedPunishmentGroups())
const templates = useImportTemplateOptions()

/** The template picked for each group, by its reason. */
const picked = ref<Record<string, string>>({})
const applying = ref<ImportedPunishmentGroup | null>(null)
const working = ref(false)
const error = ref('')
const message = ref('')

const columns = computed(() => [
  { key: 'reason' as const, label: t('importedPunishments.columns.reason') },
  { key: 'count' as const, label: t('importedPunishments.columns.count') },
  { key: 'template' as const, label: t('importedPunishments.columns.template') },
  { key: 'apply' as const, label: t('importedPunishments.columns.actions') },
])

/** The table needs a key per column, so the actions and the picker get empty placeholder fields. */
const rows = computed(() => (groups.data.value?.groups ?? []).map((group) => ({ ...group, template: '', apply: '' })))

const appliedTemplate = computed(() => (applying.value ? (picked.value[applying.value.reason] ?? '') : ''))

function ask(group: ImportedPunishmentGroup): void {
  if (!picked.value[group.reason]) return
  applying.value = group
  error.value = ''
}

async function confirm(): Promise<void> {
  const group = applying.value
  if (!group || working.value) return
  const templateKey = picked.value[group.reason]
  if (!templateKey) return
  working.value = true
  const result = await api.retemplateImportedPunishments({ templateKey, reason: group.reason })
  working.value = false
  if (!result.ok) {
    error.value = result.error.message
    return
  }
  applying.value = null
  message.value = t('importedPunishments.done', {
    linked: result.data.retemplated,
    template: templateKey,
    skipped: result.data.skipped,
  })
  await groups.reload()
}
</script>

<template>
  <section aria-labelledby="imported-punishments-title" class="flex flex-col gap-3" data-testid="imported-punishments">
    <h2 id="imported-punishments-title" class="text-title-medium">{{ t('importedPunishments.title') }}</h2>
    <p class="text-body-large text-on-surface-variant">{{ t('importedPunishments.intro') }}</p>
    <p v-if="templates.error.value" role="alert" data-testid="templates-problem" class="rounded-md-md bg-error-container p-3 text-on-error-container">
      {{ templates.error.value.message }}
    </p>
    <ResourceState :loading="groups.loading.value" :error="groups.error.value" :has-data="groups.data.value !== null" @retry="groups.reload">
      <p v-if="groups.data.value?.groups.length === 0" data-testid="imported-empty">{{ t('importedPunishments.empty') }}</p>
      <template v-else-if="groups.data.value">
        <p class="text-body-medium" data-testid="imported-total">{{ t('importedPunishments.total', { count: groups.data.value.total }) }}</p>
        <MdDataTable :label="t('importedPunishments.title')" :columns="columns" :rows="rows">
          <template #cell="{ row, column }">
            <span v-if="column.key === 'reason'" class="break-words">{{ row.reason }}</span>
            <template v-else-if="column.key === 'count'">{{ row.count }}</template>
            <div v-else-if="column.key === 'template'" class="min-w-56 py-2" :data-testid="`pick-${row.reason}`">
              <MdSelect v-model="picked[row.reason]" :label="t('importedPunishments.template')" :options="templates.options.value" />
            </div>
            <MdButton
              v-else
              variant="text"
              data-testid="apply-group"
              :disabled="!picked[row.reason]"
              :aria-label="t('importedPunishments.applyFor', { reason: row.reason })"
              @click="ask({ reason: row.reason, count: row.count })"
            >
              {{ t('importedPunishments.apply') }}
            </MdButton>
          </template>
        </MdDataTable>
      </template>
    </ResourceState>
  </section>

  <MdDialog :open="applying !== null" :headline="t('importedPunishments.confirmTitle')" @close="applying = null">
    <p v-if="applying" data-testid="confirm-body">
      {{ t('importedPunishments.confirmBody', { count: applying.count, reason: applying.reason, template: appliedTemplate }) }}
    </p>
    <p v-if="error" data-testid="error" role="alert" class="mt-3 rounded-md-md bg-error-container p-3 text-on-error-container">{{ error }}</p>
    <template #actions>
      <MdButton variant="text" data-testid="cancel" @click="applying = null">{{ t('actions.cancel') }}</MdButton>
      <MdButton data-testid="confirm" :disabled="working" @click="confirm">{{ t('importedPunishments.confirm') }}</MdButton>
    </template>
  </MdDialog>
  <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
</template>
