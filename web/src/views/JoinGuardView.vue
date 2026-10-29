<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../api/inject'
import type { GuardLevel } from '../api/types'
import DefinitionList from '../components/DefinitionList.vue'
import PageShell from '../components/PageShell.vue'
import { GUARD_LEVELS, guardSource } from '../components/guard/guardState'
import MdButton from '../components/md/MdButton.vue'
import MdDialog from '../components/md/MdDialog.vue'
import MdSelect from '../components/md/MdSelect.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import ResourceState from '../components/ResourceState.vue'
import { useResource } from '../composables/useResource'
import { formatDateTime } from '../format'
import { useSessionStore } from '../stores/session'

// The join guard: the level in force and where it comes from, and the manual level moderators set. Everyone with
// access reads the state; only moderators and admins change the manual level, after a confirmation.
const { t, locale } = useI18n()
const api = useApi()
const session = useSessionStore()
const state = useResource(() => api.joinGuard())

const chosen = ref<GuardLevel | ''>('')
const confirming = ref(false)
const saving = ref(false)
const error = ref('')
const message = ref('')

watch(
  () => state.data.value,
  (loaded) => {
    if (loaded) chosen.value = loaded.manualLevel as GuardLevel
  },
  { immediate: true },
)

const levelOptions = computed(() => GUARD_LEVELS.map((level) => ({ value: level, label: t(`guard.level.${level}`) })))
const source = computed(() => (state.data.value ? guardSource(state.data.value) : null))
const changed = computed(() => chosen.value !== '' && chosen.value !== state.data.value?.manualLevel)

const facts = computed(() => {
  const loaded = state.data.value
  if (!loaded) return []
  return [
    { term: t('guard.manualLevel'), value: t(`guard.level.${loaded.manualLevel}`) },
    { term: t('guard.unattendedLevel'), value: t(`guard.level.${loaded.automaticLevel}`) },
    { term: t('guard.updatedAt'), value: formatDateTime(loaded.updatedAt, locale.value) },
  ]
})

async function confirmChange(): Promise<void> {
  if (!chosen.value || saving.value) return
  saving.value = true
  error.value = ''
  const result = await api.setJoinGuardLevel(chosen.value)
  saving.value = false
  confirming.value = false
  if (!result.ok) {
    error.value = result.error.message
    return
  }
  message.value = t('guard.saved', { level: t(`guard.level.${chosen.value}`) })
  await state.reload()
}
</script>

<template>
  <PageShell :title="t('guard.title')" :description="t('guard.intro')">
    <ResourceState :loading="state.loading.value" :error="state.error.value" :has-data="state.data.value !== null" @retry="state.reload">
      <div v-if="state.data.value && source" class="flex flex-col gap-6">
        <section aria-labelledby="effective-heading" class="flex flex-col gap-2" data-testid="effective">
          <h2 id="effective-heading" class="text-title-medium">{{ t('guard.effective') }}</h2>
          <p class="text-headline-small" data-testid="effective-level">{{ t(`guard.level.${state.data.value.effectiveLevel}`) }}</p>
          <p class="text-body-large text-on-surface-variant" data-testid="effective-source">{{ t(`guard.source.${source}`) }}</p>
        </section>

        <DefinitionList :items="facts" />

        <section v-if="session.moderator" aria-labelledby="set-heading" class="flex flex-col gap-3">
          <h2 id="set-heading" class="text-title-medium">{{ t('guard.set.title') }}</h2>
          <p class="text-body-medium text-on-surface-variant">{{ t('guard.set.hint') }}</p>
          <div class="flex flex-wrap items-start gap-3">
            <MdSelect
              v-model="chosen"
              data-testid="guard-level"
              class="min-w-56"
              :label="t('guard.manualLevel')"
              :options="levelOptions"
            />
            <MdButton data-testid="set-level" :disabled="!changed" @click="confirming = true">{{ t('guard.set.open') }}</MdButton>
          </div>
          <p v-if="error" data-testid="error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">
            {{ error }}
          </p>
        </section>
        <p v-else data-testid="read-only" class="text-body-medium text-on-surface-variant">{{ t('guard.readOnly') }}</p>
      </div>
    </ResourceState>

    <MdDialog :open="confirming" :headline="t('guard.confirm.title')" @close="confirming = false">
      <p data-testid="confirm-body">{{ t('guard.confirm.body', { level: t(`guard.level.${chosen || 'OFF'}`) }) }}</p>
      <template #actions>
        <MdButton variant="text" data-testid="cancel" @click="confirming = false">{{ t('actions.cancel') }}</MdButton>
        <MdButton data-testid="confirm" :disabled="saving" @click="confirmChange">{{ t('guard.confirm.confirm') }}</MdButton>
      </template>
    </MdDialog>

    <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
  </PageShell>
</template>
