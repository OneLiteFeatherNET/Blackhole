<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../api/inject'
import type { GuardLevel, GuardSettings, GuardSettingsRequest } from '../api/types'
import DefinitionList from '../components/DefinitionList.vue'
import PageShell from '../components/PageShell.vue'
import GuardSettingsForm from '../components/guard/GuardSettingsForm.vue'
import { GUARD_LEVELS, guardSource } from '../components/guard/guardState'
import { windowInZone, type GuardSettingsForm as SettingsForm } from '../components/guard/guardSettings'
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
const settingsState = useResource(() => api.joinGuardSettings())

// The unattended settings: administrators edit them, everyone else reads them. Saving LOCKDOWN as the unattended level asks first.
const savingSettings = ref(false)
const settingsError = ref('')
const confirmingSettings = ref(false)
const pendingSettings = ref<GuardSettingsRequest | null>(null)

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

/** The stored settings as the form holds them. */
const initialForm = computed<SettingsForm | null>(() => {
  const loaded = settingsState.data.value
  if (!loaded) return null
  return {
    unattendedLevel: loaded.unattendedLevel === 'LOCKDOWN' ? 'LOCKDOWN' : 'STRICT',
    absenceThresholdMinutes: loaded.absenceThresholdMinutes,
    nightWindowEnabled: loaded.nightWindowEnabled,
    nightWindowStart: loaded.nightWindowStart,
    nightWindowEnd: loaded.nightWindowEnd,
    timeZone: loaded.timeZone,
  }
})

const settingsFacts = computed(() => {
  const loaded = settingsState.data.value
  if (!loaded) return []
  return [
    { term: t('guard.settings.level'), value: t(`guard.level.${loaded.unattendedLevel}`) },
    { term: t('guard.settings.threshold'), value: t('guard.settings.minutesValue', { n: loaded.absenceThresholdMinutes }) },
    {
      term: t('guard.settings.nightWindow'),
      value: loaded.nightWindowEnabled ? `${loaded.nightWindowStart} - ${loaded.nightWindowEnd}` : t('guard.settings.disabled'),
    },
    { term: t('guard.settings.zone'), value: loaded.timeZone },
  ]
})

/** The next night window in the viewer's own zone, with the configured zone named next to it. */
const nextWindowText = computed(() => {
  const loaded: GuardSettings | null = settingsState.data.value
  if (!loaded) return ''
  if (!loaded.nightWindowEnabled || loaded.nextNightWindowStart == null || loaded.nextNightWindowEnd == null) {
    return t('guard.settings.noNightWindow')
  }
  const shown = windowInZone(loaded.nextNightWindowStart, loaded.nextNightWindowEnd, locale.value)
  return t('guard.settings.nextWindow', { window: t('guard.settings.window', { start: shown.start, end: shown.end }), zone: loaded.timeZone })
})

function requestSettingsSave(request: GuardSettingsRequest): void {
  if (request.unattendedLevel === 'LOCKDOWN' && settingsState.data.value?.unattendedLevel !== 'LOCKDOWN') {
    pendingSettings.value = request
    confirmingSettings.value = true
    return
  }
  void saveSettings(request)
}

async function saveSettings(request: GuardSettingsRequest): Promise<void> {
  if (savingSettings.value) return
  savingSettings.value = true
  settingsError.value = ''
  const result = await api.setJoinGuardSettings(request)
  savingSettings.value = false
  confirmingSettings.value = false
  if (!result.ok) {
    settingsError.value = result.error.message
    return
  }
  message.value = t('guard.settings.saved')
  await settingsState.reload()
}

async function confirmSettingsSave(): Promise<void> {
  if (pendingSettings.value) await saveSettings(pendingSettings.value)
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

    <section aria-labelledby="settings-heading" class="mt-8 flex flex-col gap-4" data-testid="settings">
      <h2 id="settings-heading" class="text-title-medium">{{ t('guard.settings.title') }}</h2>
      <p class="text-body-medium text-on-surface-variant">{{ t('guard.settings.intro') }}</p>
      <ResourceState
        :loading="settingsState.loading.value"
        :error="settingsState.error.value"
        :has-data="settingsState.data.value !== null"
        @retry="settingsState.reload"
      >
        <div v-if="settingsState.data.value" class="flex flex-col gap-4">
          <GuardSettingsForm
            v-if="session.administrator && initialForm"
            :initial="initialForm"
            :saving="savingSettings"
            :error="settingsError"
            @save="requestSettingsSave"
          />
          <template v-else>
            <div data-testid="settings-read-only" class="flex flex-col gap-3">
              <DefinitionList :items="settingsFacts" />
              <p class="text-body-medium text-on-surface-variant">{{ t('guard.settings.readOnly') }}</p>
            </div>
          </template>
          <p data-testid="settings-next-window" class="text-body-large">{{ nextWindowText }}</p>
        </div>
      </ResourceState>
    </section>

    <MdDialog :open="confirmingSettings" :headline="t('guard.settings.confirm.title', { level: t('guard.level.LOCKDOWN') })" @close="confirmingSettings = false">
      <p data-testid="settings-confirm-body">{{ t('guard.settings.confirm.body', { level: t('guard.level.LOCKDOWN') }) }}</p>
      <template #actions>
        <MdButton variant="text" data-testid="settings-confirm-cancel" @click="confirmingSettings = false">{{ t('actions.cancel') }}</MdButton>
        <MdButton data-testid="settings-confirm-accept" :disabled="savingSettings" @click="confirmSettingsSave">{{ t('guard.settings.save') }}</MdButton>
      </template>
    </MdDialog>

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
