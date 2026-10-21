<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { EnforcementKind, Section } from '../../api/types'
import { formatDuration } from '../../format'
import MdButton from '../md/MdButton.vue'
import MdCheckbox from '../md/MdCheckbox.vue'
import MdDialog from '../md/MdDialog.vue'
import MdSelect from '../md/MdSelect.vue'
import MdTextField from '../md/MdTextField.vue'
import { sectionFieldErrors, type FieldErrors } from '../templates/fieldErrors'

const props = defineProps<{ open: boolean; section: Section | null }>()
const emit = defineEmits<{ close: []; saved: [message: string] }>()

const { t, locale } = useI18n()
const api = useApi()

// Number inputs are kept as text so an empty or malformed value can be told from 0.
const form = ref({
  key: '',
  displayName: '',
  enforcement: 'BAN' as EnforcementKind,
  servers: '',
  baseScore: '1000',
  softThreshold: '',
  softDuration: '',
  permanentThreshold: '300',
  pardonScore: '700',
  permanentReason: '',
  decayPerDay: '10',
  enabled: true,
})
const errors = ref<FieldErrors>({ fields: {}, general: [] })
const saving = ref(false)

const editing = computed(() => props.section !== null)
const isServer = computed(() => form.value.enforcement === 'SERVER')
const enforcementOptions = computed(() =>
  (['MUTE', 'BAN', 'SERVER'] as const).map((value) => ({ value, label: t(`enforcement.${value}`) })),
)

watch(
  () => props.open,
  (open) => {
    if (!open) return
    const source = props.section
    form.value = {
      key: source?.key ?? '',
      displayName: source?.displayName ?? '',
      enforcement: source?.enforcement ?? 'BAN',
      servers: source?.servers.join(', ') ?? '',
      baseScore: String(source?.baseScore ?? 1000),
      softThreshold: source?.softThreshold === null || source?.softThreshold === undefined ? '' : String(source.softThreshold),
      softDuration: source?.softDuration ?? '',
      permanentThreshold: String(source?.permanentThreshold ?? 300),
      pardonScore: String(source?.pardonScore ?? 700),
      permanentReason: source?.permanentReason ?? '',
      decayPerDay: String(source?.decayPerDay ?? 10),
      enabled: source?.enabled ?? true,
    }
    errors.value = { fields: {}, general: [] }
  },
  { immediate: true },
)

const fieldError = (name: string) => errors.value.fields[name] ?? ''
const WHOLE_NUMBER = /^-?\d+$/
const whole = (value: unknown) => WHOLE_NUMBER.test(String(value).trim())

async function save(): Promise<void> {
  if (saving.value) return
  const local: Record<string, string> = {}
  for (const name of ['baseScore', 'permanentThreshold', 'pardonScore', 'decayPerDay'] as const) {
    if (!whole(form.value[name])) local[name] = t('sections.editor.invalidNumber')
  }
  // The soft threshold is optional: empty means the section has none.
  if (String(form.value.softThreshold).trim() !== '' && !whole(form.value.softThreshold)) {
    local.softThreshold = t('sections.editor.invalidNumber')
  }
  if (Object.keys(local).length > 0) {
    errors.value = { fields: local, general: [] }
    return
  }

  saving.value = true
  const hasSoft = String(form.value.softThreshold).trim() !== ''
  const draft = {
    key: form.value.key,
    displayName: form.value.displayName,
    enforcement: form.value.enforcement,
    servers: form.value.servers.split(',').map((pattern) => pattern.trim()).filter(Boolean),
    baseScore: Number(form.value.baseScore),
    softThreshold: hasSoft ? Number(form.value.softThreshold) : null,
    softDuration: hasSoft ? form.value.softDuration.trim() || null : null,
    permanentThreshold: Number(form.value.permanentThreshold),
    pardonScore: Number(form.value.pardonScore),
    permanentReason: form.value.permanentReason,
    decayPerDay: Number(form.value.decayPerDay),
    enabled: form.value.enabled,
  }
  const result = editing.value ? await api.updateSection(draft) : await api.createSection(draft)
  saving.value = false
  if (!result.ok) {
    errors.value = sectionFieldErrors(result.error.message)
    if (result.error.kind === 'NETWORK') errors.value.general.push(result.error.message)
    return
  }
  emit('saved', t('sections.saved'))
}
</script>

<template>
  <MdDialog
    :open="open"
    wide
    :headline="section ? t('sections.editor.editTitle', { key: section.key }) : t('sections.editor.newTitle')"
    @close="emit('close')"
  >
    <form class="flex flex-col gap-5 pt-2" novalidate @submit.prevent="save">
      <div v-if="errors.general.length" data-testid="error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">
        <p v-for="message in errors.general" :key="message">{{ message }}</p>
      </div>

      <div class="grid gap-5 sm:grid-cols-2">
        <MdTextField
          v-model="form.key"
          required
          :disabled="editing"
          :label="t('sections.editor.key')"
          :error="fieldError('key')"
          :supporting-text="t('sections.editor.keyHint')"
        />
        <MdTextField v-model="form.displayName" required :label="t('sections.editor.name')" :error="fieldError('displayName')" />
      </div>

      <MdSelect v-model="form.enforcement" :label="t('sections.editor.enforcement')" :options="enforcementOptions" />
      <p v-if="fieldError('enforcement')" role="alert" class="-mt-3 px-4 text-body-small text-error">{{ fieldError('enforcement') }}</p>
      <MdTextField
        v-if="isServer"
        v-model="form.servers"
        required
        :label="t('sections.editor.servers')"
        :error="fieldError('servers')"
        :supporting-text="t('sections.editor.serversHint')"
      />

      <fieldset class="grid gap-5 rounded-md-md border border-outline-variant p-4 sm:grid-cols-2">
        <legend class="px-2 text-title-small">{{ t('sections.editor.thresholds') }}</legend>
        <MdTextField v-model="form.baseScore" type="number" required :label="t('sections.editor.baseScore')" :error="fieldError('baseScore')" :supporting-text="t('sections.editor.baseScoreHint')" />
        <MdTextField v-model="form.pardonScore" type="number" required :label="t('sections.editor.pardonScore')" :error="fieldError('pardonScore')" :supporting-text="t('sections.editor.pardonScoreHint')" />
        <MdTextField v-model="form.softThreshold" type="number" :label="t('sections.editor.softThreshold')" :error="fieldError('softThreshold')" :supporting-text="t('sections.editor.softThresholdHint')" />
        <MdTextField
          v-model="form.softDuration"
          :label="t('sections.editor.softDuration')"
          :error="fieldError('softDuration')"
          :supporting-text="form.softDuration ? formatDuration(form.softDuration, locale) : t('sections.editor.softDurationHint')"
        />
        <MdTextField v-model="form.permanentThreshold" type="number" required :label="t('sections.editor.permanentThreshold')" :error="fieldError('permanentThreshold')" :supporting-text="t('sections.editor.permanentThresholdHint')" />
        <MdTextField v-model="form.decayPerDay" type="number" required :label="t('sections.editor.decayPerDay')" :error="fieldError('decayPerDay')" :supporting-text="t('sections.editor.decayHint')" />
      </fieldset>

      <MdTextField v-model="form.permanentReason" required :label="t('sections.editor.permanentReason')" :error="fieldError('permanentReason')" :supporting-text="t('sections.editor.permanentReasonHint')" />
      <MdCheckbox v-if="editing" v-model="form.enabled" :label="t('sections.editor.enabled')" :supporting-text="t('sections.editor.enabledHint')" />
    </form>
    <template #actions>
      <MdButton variant="text" data-testid="cancel" @click="emit('close')">{{ t('actions.cancel') }}</MdButton>
      <MdButton data-testid="save" :disabled="saving" @click="save">{{ t('sections.editor.save') }}</MdButton>
    </template>
  </MdDialog>
</template>
