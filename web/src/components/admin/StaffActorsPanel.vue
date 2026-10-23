<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { StaffActor } from '../../api/types'
import { useResource } from '../../composables/useResource'
import { formatDateTime } from '../../format'
import MdButton from '../md/MdButton.vue'
import MdDataTable from '../md/MdDataTable.vue'
import MdDialog from '../md/MdDialog.vue'
import MdSnackbar from '../md/MdSnackbar.vue'
import ResourceState from '../ResourceState.vue'

const { t, locale } = useI18n()
const api = useApi()

const actors = useResource(() => api.staffActors())
const anonymizing = ref<StaffActor | null>(null)
const anonymizingNow = ref(false)
const error = ref('')
const message = ref('')

const columns = computed(() => [
  { key: 'displayName' as const, label: t('staffActors.columns.name') },
  { key: 'oid' as const, label: t('staffActors.columns.oid') },
  { key: 'kind' as const, label: t('staffActors.columns.kind') },
  { key: 'lastSeenAt' as const, label: t('staffActors.columns.lastSeen') },
  { key: 'firstSeenAt' as const, label: t('staffActors.columns.actions') },
])

const nameOf = (actor: StaffActor): string => actor.displayName || t('staffActors.unnamed')

function ask(actor: StaffActor): void {
  anonymizing.value = actor
  error.value = ''
}

async function confirm(): Promise<void> {
  const actor = anonymizing.value
  if (!actor || anonymizingNow.value) return
  anonymizingNow.value = true
  const result = await api.anonymizeStaffActor(actor.oid)
  anonymizingNow.value = false
  if (!result.ok) {
    error.value = result.error.message
    return
  }
  anonymizing.value = null
  message.value = t('staffActors.done', { name: nameOf(actor) })
  await actors.reload()
}
</script>

<template>
  <section aria-labelledby="staff-actors-title" class="flex flex-col gap-3" data-testid="staff-actors">
    <h2 id="staff-actors-title" class="text-title-large">{{ t('staffActors.title') }}</h2>
    <p class="text-body-large text-on-surface-variant">{{ t('staffActors.intro') }}</p>
    <ResourceState :loading="actors.loading.value" :error="actors.error.value" :has-data="actors.data.value !== null" @retry="actors.reload">
      <p v-if="actors.data.value?.length === 0" data-testid="staff-actors-empty">{{ t('staffActors.empty') }}</p>
      <MdDataTable v-else-if="actors.data.value" :label="t('staffActors.title')" :columns="columns" :rows="actors.data.value">
        <template #cell="{ row, column }">
          <template v-if="column.key === 'displayName'">{{ nameOf(row) }}</template>
          <code v-else-if="column.key === 'oid'" class="font-mono text-body-small">{{ row.oid }}</code>
          <template v-else-if="column.key === 'kind'">{{ t(`staffActors.kind.${row.kind}`) }}</template>
          <template v-else-if="column.key === 'lastSeenAt'">{{ formatDateTime(row.lastSeenAt, locale) }}</template>
          <MdButton
            v-else
            variant="text"
            :data-testid="`anonymize-${row.oid}`"
            :aria-label="t('staffActors.anonymizeFor', { name: nameOf(row) })"
            @click="ask(row)"
          >
            {{ t('staffActors.anonymize') }}
          </MdButton>
        </template>
      </MdDataTable>
    </ResourceState>
  </section>

  <MdDialog :open="anonymizing !== null" :headline="t('staffActors.dialogTitle', { name: anonymizing ? nameOf(anonymizing) : '' })" @close="anonymizing = null">
    <p>{{ t('staffActors.consequencesIntro') }}</p>
    <ul class="mt-2 list-disc pl-5" data-testid="consequences">
      <li>{{ t('staffActors.consequenceName') }}</li>
      <li>{{ t('staffActors.consequenceHistory') }}</li>
      <li>{{ t('staffActors.consequenceLink') }}</li>
      <li class="font-medium">{{ t('staffActors.consequenceIrreversible') }}</li>
    </ul>
    <p v-if="error" data-testid="error" role="alert" class="mt-3 rounded-md-md bg-error-container p-3 text-on-error-container">{{ error }}</p>
    <template #actions>
      <MdButton variant="text" data-testid="cancel" @click="anonymizing = null">{{ t('actions.cancel') }}</MdButton>
      <MdButton data-testid="confirm" :disabled="anonymizingNow" @click="confirm">{{ t('staffActors.confirm') }}</MdButton>
    </template>
  </MdDialog>
  <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
</template>
