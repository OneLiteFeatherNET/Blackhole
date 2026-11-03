<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { useApi } from '../api/inject'
import type { GuardSettings, GuardSettingsRequest } from '../api/types'
import DefinitionList from '../components/DefinitionList.vue'
import PageShell from '../components/PageShell.vue'
import GuardSettingsForm from '../components/guard/GuardSettingsForm.vue'
import { windowInZone, type GuardSettingsForm as SettingsForm } from '../components/guard/guardSettings'
import MdButton from '../components/md/MdButton.vue'
import MdDialog from '../components/md/MdDialog.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import ResourceState from '../components/ResourceState.vue'
import { useResource } from '../composables/useResource'
import { useRoutedDialog } from '../router/routedDialog'
import { useSessionStore } from '../stores/session'

// The unattended join guard settings. Everyone with access reads them; an administrator edits them in the child
// route `edit`, and saving LOCKDOWN as the unattended level asks first.
const { t, locale } = useI18n()
const api = useApi()
const session = useSessionStore()

const settingsState = useResource(() => api.joinGuardSettings())
const edit = useRoutedDialog({ parent: 'guard-settings', child: 'guard-settings-edit' })

const savingSettings = ref(false)
const settingsError = ref('')
const confirmingSettings = ref(false)
const pendingSettings = ref<GuardSettingsRequest | null>(null)
const message = ref('')

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
  await edit.close()
  message.value = t('guard.settings.saved')
  await settingsState.reload()
}

async function confirmSettingsSave(): Promise<void> {
  if (pendingSettings.value) await saveSettings(pendingSettings.value)
}
</script>

<template>
  <PageShell :title="t('guard.settings.title')" :description="t('guard.settings.intro')">
    <p class="mb-4">
      <RouterLink :to="{ name: 'guard' }" data-testid="back-to-guard" class="inline-flex min-h-12 items-center text-primary underline underline-offset-2 focus-ring">
        {{ t('guard.settings.backToGuard') }}
      </RouterLink>
    </p>

    <ResourceState
      :loading="settingsState.loading.value"
      :error="settingsState.error.value"
      :has-data="settingsState.data.value !== null"
      @retry="settingsState.reload"
    >
      <div v-if="settingsState.data.value" class="flex flex-col gap-4">
        <div v-if="session.administrator" class="flex flex-wrap gap-3">
          <MdButton as="link" data-testid="edit-settings" :to="{ name: 'guard-settings-edit' }">{{ t('guard.settings.edit') }}</MdButton>
        </div>
        <div :data-testid="session.administrator ? 'settings-facts' : 'settings-read-only'" class="flex flex-col gap-3">
          <DefinitionList :items="settingsFacts" />
          <p v-if="!session.administrator" class="text-body-medium text-on-surface-variant">{{ t('guard.settings.readOnly') }}</p>
        </div>
        <p data-testid="settings-next-window" class="text-body-large">{{ nextWindowText }}</p>
      </div>
    </ResourceState>

    <MdDialog
      :open="edit.open.value && session.administrator"
      :headline="t('guard.settings.edit')"
      wide
      @close="edit.close"
    >
      <GuardSettingsForm
        v-if="initialForm"
        :initial="initialForm"
        :saving="savingSettings"
        :error="settingsError"
        @save="requestSettingsSave"
      />
    </MdDialog>

    <MdDialog :open="confirmingSettings" :headline="t('guard.settings.confirm.title', { level: t('guard.level.LOCKDOWN') })" @close="confirmingSettings = false">
      <p data-testid="settings-confirm-body">{{ t('guard.settings.confirm.body', { level: t('guard.level.LOCKDOWN') }) }}</p>
      <template #actions>
        <MdButton variant="text" data-testid="settings-confirm-cancel" @click="confirmingSettings = false">{{ t('actions.cancel') }}</MdButton>
        <MdButton data-testid="settings-confirm-accept" :disabled="savingSettings" @click="confirmSettingsSave">{{ t('guard.settings.save') }}</MdButton>
      </template>
    </MdDialog>

    <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
  </PageShell>
</template>
