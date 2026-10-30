<script setup lang="ts">
// The editable unattended settings of the join guard. It checks the bounds the server also checks, and emits the whole
// settings object only when they hold; the view decides whether a confirmation comes first.
import { computed, reactive, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { GuardSettingsRequest } from '../../api/types'
import MdButton from '../md/MdButton.vue'
import MdCheckbox from '../md/MdCheckbox.vue'
import MdSelect from '../md/MdSelect.vue'
import MdTextField from '../md/MdTextField.vue'
import { settingsProblems, THRESHOLD_MAX_MINUTES, THRESHOLD_MIN_MINUTES, zoneChoices, type GuardSettingsForm } from './guardSettings'

const props = defineProps<{ initial: GuardSettingsForm; saving: boolean; error: string }>()
const emit = defineEmits<{ save: [request: GuardSettingsRequest] }>()
const { t } = useI18n()

const form = reactive<GuardSettingsForm>({ ...props.initial })
// A reload of the stored settings replaces the form; edits made since the last load are dropped on purpose.
watch(
  () => props.initial,
  (next) => Object.assign(form, next),
)

const problems = computed(() => settingsProblems(form))
const invalid = computed(() => Object.keys(problems.value).length > 0)

const levelOptions = computed(() => [
  { value: 'STRICT', label: t('guard.level.STRICT') },
  { value: 'LOCKDOWN', label: t('guard.level.LOCKDOWN') },
])
const zoneOptions = computed(() => {
  const zones = zoneChoices()
  const current = form.timeZone
  const listed = current === '' || zones.includes(current) ? zones : [current, ...zones]
  return listed.map((zone) => ({ value: zone, label: zone }))
})

// The text field holds a string; an empty field is no threshold at all, so the form cannot send one by accident.
const threshold = computed({
  get: () => (form.absenceThresholdMinutes === null ? '' : String(form.absenceThresholdMinutes)),
  // A number input hands over a number, a text input a string; both end up as a number or nothing.
  set: (value: string | number) => {
    const text = String(value).trim()
    form.absenceThresholdMinutes = text === '' ? null : Number(text)
  },
})
const level = computed({
  get: () => form.unattendedLevel,
  set: (value: string) => {
    form.unattendedLevel = value === 'LOCKDOWN' ? 'LOCKDOWN' : 'STRICT'
  },
})

function problemText(field: keyof ReturnType<typeof settingsProblems>): string {
  const key = problems.value[field]
  return key ? t(key, { min: THRESHOLD_MIN_MINUTES, max: THRESHOLD_MAX_MINUTES }) : ''
}

function submit(): void {
  const minutes = form.absenceThresholdMinutes
  if (invalid.value || minutes === null || props.saving) return
  emit('save', {
    unattendedLevel: form.unattendedLevel,
    absenceThresholdMinutes: minutes,
    nightWindowEnabled: form.nightWindowEnabled,
    nightWindowStart: form.nightWindowStart,
    nightWindowEnd: form.nightWindowEnd,
    timeZone: form.timeZone,
  })
}
</script>

<template>
  <form data-testid="settings-form" class="flex flex-col gap-4" @submit.prevent="submit">
    <MdSelect
      v-model="level"
      data-testid="settings-level"
      class="min-w-56"
      :label="t('guard.settings.level')"
      :options="levelOptions"
    />
    <MdTextField
      v-model="threshold"
      data-testid="settings-threshold"
      type="number"
      required
      :label="t('guard.settings.threshold')"
      :supporting-text="t('guard.settings.thresholdHelp')"
      :error="problemText('absenceThresholdMinutes')"
    />
    <MdCheckbox v-model="form.nightWindowEnabled" data-testid="settings-night-enabled" :label="t('guard.settings.nightEnabled')" />
    <div class="grid items-start gap-4 sm:grid-cols-2">
      <MdTextField
        v-model="form.nightWindowStart"
        data-testid="settings-night-start"
        type="time"
        required
        :label="t('guard.settings.nightStart')"
        :error="problemText('nightWindow')"
      />
      <MdTextField
        v-model="form.nightWindowEnd"
        data-testid="settings-night-end"
        type="time"
        required
        :label="t('guard.settings.nightEnd')"
        :supporting-text="t('guard.settings.nightEndHelp')"
      />
    </div>
    <MdSelect
      v-model="form.timeZone"
      data-testid="settings-zone"
      class="min-w-56"
      :label="t('guard.settings.zone')"
      :options="zoneOptions"
      :error="problemText('timeZone')"
    />
    <p v-if="props.error" data-testid="settings-error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">
      {{ props.error }}
    </p>
    <div>
      <MdButton data-testid="settings-save" type="button" :disabled="invalid || props.saving" @click="submit">
        {{ t('guard.settings.save') }}
      </MdButton>
    </div>
  </form>
</template>
