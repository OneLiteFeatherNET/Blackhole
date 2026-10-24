<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../api/inject'
import type { ClientRule, ClientRuleHit } from '../api/types'
import ClientRuleEditor from '../components/clientrules/ClientRuleEditor.vue'
import ClientRuleHitTable from '../components/clientrules/ClientRuleHitTable.vue'
import MdButton from '../components/md/MdButton.vue'
import MdDataTable from '../components/md/MdDataTable.vue'
import MdDialog from '../components/md/MdDialog.vue'
import MdProgress from '../components/md/MdProgress.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import ResourceState from '../components/ResourceState.vue'
import StatusPill from '../components/StatusPill.vue'
import { useResource } from '../composables/useResource'
import { useSessionStore } from '../stores/session'

const { t } = useI18n()
const api = useApi()
const session = useSessionStore()

const rules = useResource(() => api.clientRules())

const editorOpen = ref(false)
const editing = ref<ClientRule | null>(null)
const deleting = ref<ClientRule | null>(null)
const deleteError = ref('')
const deletingNow = ref(false)
const message = ref('')

const hitsOf = ref<ClientRule | null>(null)
const hits = ref<ClientRuleHit[] | null>(null)
const hitsLoading = ref(false)
const hitsError = ref('')

const columns = computed(() => [
  { key: 'key' as const, label: t('clientRules.columns.rule') },
  { key: 'kind' as const, label: t('clientRules.columns.match') },
  { key: 'scope' as const, label: t('clientRules.columns.scope') },
  { key: 'action' as const, label: t('clientRules.columns.action') },
  { key: 'hitCount' as const, label: t('clientRules.columns.hits') },
  { key: 'enabled' as const, label: t('clientRules.columns.actions') },
])

function matchText(rule: ClientRule): string {
  if (rule.kind === 'PROTOCOL') return `${rule.protocolMin ?? '?'} - ${rule.protocolMax ?? '?'}`
  return rule.patterns.join(', ')
}

function openEditor(rule: ClientRule | null): void {
  editing.value = rule
  editorOpen.value = true
}

async function saved(done: string): Promise<void> {
  editorOpen.value = false
  message.value = done
  await rules.reload()
}

function askDelete(rule: ClientRule): void {
  deleting.value = rule
  deleteError.value = ''
}

async function confirmDelete(): Promise<void> {
  if (!deleting.value || deletingNow.value) return
  deletingNow.value = true
  const result = await api.deleteClientRule(deleting.value.key)
  deletingNow.value = false
  if (!result.ok) {
    deleteError.value = result.error.message
    return
  }
  deleting.value = null
  message.value = t('clientRules.deleted')
  await rules.reload()
}

async function showHits(rule: ClientRule): Promise<void> {
  hitsOf.value = rule
  hits.value = null
  hitsError.value = ''
  hitsLoading.value = true
  const requested = rule.key
  const result = await api.clientRuleHits(requested)
  if (hitsOf.value?.key !== requested) return // a newer selection superseded this answer
  hitsLoading.value = false
  if (result.ok) hits.value = result.data
  else hitsError.value = result.error.message
}
</script>

<template>
  <ResourceState
    :loading="rules.loading.value"
    :error="rules.error.value"
    :has-data="rules.data.value !== null"
    @retry="rules.reload"
  >
    <div class="flex flex-col gap-4">
      <div class="flex flex-wrap items-center gap-3">
        <p class="min-w-0 flex-1 text-body-large text-on-surface-variant">{{ t('clientRules.intro') }}</p>
        <MdButton v-if="session.administrator" data-testid="new-client-rule" @click="openEditor(null)">{{ t('clientRules.new') }}</MdButton>
      </div>
      <p role="note" data-testid="honesty" class="rounded-md-md bg-tertiary-container p-3 text-body-medium text-on-tertiary-container">
        {{ t('clientRules.honesty') }}
      </p>
      <p v-if="!session.administrator" data-testid="read-only" class="text-body-medium text-on-surface-variant">{{ t('clientRules.readOnly') }}</p>

      <p v-if="rules.data.value && rules.data.value.length === 0" data-testid="empty" class="text-body-large text-on-surface-variant">
        {{ t('clientRules.empty') }}
      </p>
      <MdDataTable v-else-if="rules.data.value" :label="t('clientRules.title')" :columns="columns" :rows="rules.data.value">
        <template #cell="{ row, column }">
          <template v-if="column.key === 'key'">
            <p class="text-label-large">{{ row.key }}</p>
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
          <template v-else-if="column.key === 'hitCount'">
            <MdButton variant="text" :data-testid="`hits-${row.key}`" @click="showHits(row)">
              {{ t('clientRules.hitCount', { n: row.hitCount ?? 0 }) }}
            </MdButton>
          </template>
          <div v-else-if="column.key === 'enabled' && session.administrator" class="flex gap-1">
            <MdButton variant="text" :data-testid="`edit-${row.key}`" @click="openEditor(row)">{{ t('clientRules.edit') }}</MdButton>
            <MdButton variant="text" :data-testid="`delete-${row.key}`" @click="askDelete(row)">{{ t('clientRules.delete.open') }}</MdButton>
          </div>
          <template v-else />
        </template>
      </MdDataTable>
    </div>
  </ResourceState>

  <ClientRuleEditor :open="editorOpen" :rule="editing" @close="editorOpen = false" @saved="saved" />

  <MdDialog :open="deleting !== null" :headline="t('clientRules.delete.title', { key: deleting?.key ?? '' })" @close="deleting = null">
    <p>{{ t('clientRules.delete.body') }}</p>
    <p v-if="deleteError" data-testid="error" role="alert" class="mt-3 rounded-md-md bg-error-container p-3 text-on-error-container">
      {{ deleteError }}
    </p>
    <template #actions>
      <MdButton variant="text" data-testid="cancel" @click="deleting = null">{{ t('actions.cancel') }}</MdButton>
      <MdButton data-testid="confirm" :disabled="deletingNow" @click="confirmDelete">{{ t('clientRules.delete.confirm') }}</MdButton>
    </template>
  </MdDialog>

  <MdDialog :open="hitsOf !== null" wide :headline="t('clientRules.hits.title', { key: hitsOf?.key ?? '' })" @close="hitsOf = null">
    <div aria-live="polite" class="flex flex-col gap-3" data-testid="hits-dialog">
      <p class="text-body-medium text-on-surface-variant">{{ t('clientRules.hits.hint') }}</p>
      <MdProgress v-if="hitsLoading" :label="t('state.loading')" />
      <p v-else-if="hitsError" data-testid="hits-error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">{{ hitsError }}</p>
      <p v-else-if="hits && hits.length === 0" data-testid="hits-empty" class="text-body-large text-on-surface-variant">{{ t('clientRules.hits.empty') }}</p>
      <ClientRuleHitTable v-else-if="hits" :hits="hits" by="player" :label="t('clientRules.hits.title', { key: hitsOf?.key ?? '' })" />
    </div>
    <template #actions>
      <MdButton variant="text" data-testid="close-hits" @click="hitsOf = null">{{ t('clientRules.hits.close') }}</MdButton>
    </template>
  </MdDialog>

  <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
</template>
