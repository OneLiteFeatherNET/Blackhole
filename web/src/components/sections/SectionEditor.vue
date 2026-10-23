<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { CloudNetCatalogue, EnforcementKind, Section, ServerTargetKind } from '../../api/types'
import { parseDuration } from '../../duration'
import MdButton from '../md/MdButton.vue'
import MdCheckbox from '../md/MdCheckbox.vue'
import MdDialog from '../md/MdDialog.vue'
import MdDurationField from '../md/MdDurationField.vue'
import MdSelect from '../md/MdSelect.vue'
import MdTextField from '../md/MdTextField.vue'
import { sectionFieldErrors, type FieldErrors } from '../templates/fieldErrors'
import CloudNetTargetPicker from './CloudNetTargetPicker.vue'

const props = defineProps<{ open: boolean; section: Section | null }>()
const emit = defineEmits<{ close: []; saved: [message: string] }>()

const { t } = useI18n()
const api = useApi()

// Number inputs are kept as text so an empty or malformed value can be told from 0.
const form = ref({
  key: '',
  displayName: '',
  enforcement: 'BAN' as EnforcementKind,
  targetKind: 'PATTERN' as ServerTargetKind,
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
// Names of a CloudNet kind live apart from the pattern text, so switching back and forth loses neither.
const names = ref<string[]>([])
const picker = ref<InstanceType<typeof CloudNetTargetPicker> | null>(null)
const errors = ref<FieldErrors>({ fields: {}, general: [] })
const saving = ref(false)

// The catalogue is only asked for when a CloudNet kind is chosen, once per opened editor. A failed
// request and an empty catalogue both leave `catalogue` null / unavailable: the picker is then free text.
const catalogue = ref<CloudNetCatalogue | null>(null)
const catalogueLoading = ref(false)
let catalogueRequested = false

const SOFT_CHIPS = ['1d', '3d', '7d']

const editing = computed(() => props.section !== null)
const isServer = computed(() => form.value.enforcement === 'SERVER')
const cloudnetKind = computed(() => {
  const kind = form.value.targetKind
  return isServer.value && kind !== 'PATTERN' ? kind : null
})
const targetKindOptions = computed(() =>
  (['PATTERN', 'CLOUDNET_TASK', 'CLOUDNET_GROUP', 'CLOUDNET_SERVICE'] as const).map((value) => ({
    value,
    label: t(`sections.targetKind.${value}`),
  })),
)

async function loadCatalogue(): Promise<void> {
  if (catalogueRequested) return
  catalogueRequested = true
  catalogueLoading.value = true
  const result = await api.cloudnetCatalogue()
  catalogue.value = result.ok ? result.data : null
  catalogueLoading.value = false
}

watch(cloudnetKind, (kind) => {
  if (kind) void loadCatalogue()
})

// A task name means nothing as a group or service name, so a change between CloudNet kinds starts empty.
function chooseTargetKind(kind: string): void {
  if (kind !== form.value.targetKind && kind !== 'PATTERN' && form.value.targetKind !== 'PATTERN') names.value = []
  form.value.targetKind = kind as ServerTargetKind
}
const enforcementOptions = computed(() =>
  (['MUTE', 'BAN', 'SERVER'] as const).map((value) => ({ value, label: t(`enforcement.${value}`) })),
)

watch(
  () => props.open,
  (open) => {
    if (!open) return
    const source = props.section
    const kind = source?.targetKind ?? 'PATTERN'
    names.value = kind === 'PATTERN' ? [] : [...(source?.servers ?? [])]
    catalogue.value = null
    catalogueLoading.value = false
    catalogueRequested = false
    form.value = {
      key: source?.key ?? '',
      displayName: source?.displayName ?? '',
      enforcement: source?.enforcement ?? 'BAN',
      targetKind: kind,
      servers: kind === 'PATTERN' ? (source?.servers.join(', ') ?? '') : '',
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
    if (source?.enforcement === 'SERVER' && kind !== 'PATTERN') void loadCatalogue()
  },
  { immediate: true },
)

const fieldError = (name: string) => errors.value.fields[name] ?? ''

// A message about a field is stale as soon as the form is edited.
watch(form, () => {
  if (Object.keys(errors.value.fields).length > 0) errors.value = { fields: {}, general: errors.value.general }
}, { deep: true })
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
  const hasSoft = String(form.value.softThreshold).trim() !== ''
  // The duration only matters with a soft threshold; an empty one is left to the backend to explain.
  const soft = parseDuration(form.value.softDuration)
  if (hasSoft && form.value.softDuration.trim() !== '' && !soft.ok) local.softDuration = t(`duration.errors.${soft.reason}`)
  if (Object.keys(local).length > 0) {
    errors.value = { fields: local, general: [] }
    return
  }

  picker.value?.commit()
  saving.value = true
  const draft = {
    key: form.value.key,
    displayName: form.value.displayName,
    enforcement: form.value.enforcement,
    targetKind: form.value.targetKind,
    servers:
      form.value.targetKind === 'PATTERN'
        ? form.value.servers.split(',').map((pattern) => pattern.trim()).filter(Boolean)
        : names.value.map((name) => name.trim()).filter(Boolean),
    baseScore: Number(form.value.baseScore),
    softThreshold: hasSoft ? Number(form.value.softThreshold) : null,
    softDuration: hasSoft && soft.ok ? soft.iso : null,
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
      <MdSelect
        v-if="isServer"
        :model-value="form.targetKind"
        :label="t('sections.editor.targetKind')"
        :options="targetKindOptions"
        @update:model-value="chooseTargetKind"
        :supporting-text="t('sections.editor.targetKindHint')"
      />
      <MdTextField
        v-if="isServer && !cloudnetKind"
        v-model="form.servers"
        required
        :label="t('sections.editor.servers')"
        :error="fieldError('servers')"
        :supporting-text="t('sections.editor.serversHint')"
      />
      <CloudNetTargetPicker
        v-if="cloudnetKind"
        ref="picker"
        v-model="names"
        :kind="cloudnetKind"
        :catalogue="catalogue"
        :loading="catalogueLoading"
        :error="fieldError('servers')"
      />
      <p v-if="cloudnetKind === 'CLOUDNET_SERVICE'" role="note" data-testid="service-warning" class="rounded-md-md bg-tertiary-container p-3 text-body-medium text-on-tertiary-container">
        <strong>{{ t('sections.editor.serviceWarningTitle') }}</strong> {{ t('sections.editor.serviceWarning') }}
      </p>

      <fieldset class="grid gap-5 rounded-md-md border border-outline-variant p-4 sm:grid-cols-2">
        <legend class="px-2 text-title-small">{{ t('sections.editor.thresholds') }}</legend>
        <MdTextField v-model="form.baseScore" type="number" required :label="t('sections.editor.baseScore')" :error="fieldError('baseScore')" :supporting-text="t('sections.editor.baseScoreHint')" />
        <MdTextField v-model="form.pardonScore" type="number" required :label="t('sections.editor.pardonScore')" :error="fieldError('pardonScore')" :supporting-text="t('sections.editor.pardonScoreHint')" />
        <MdTextField v-model="form.softThreshold" type="number" :label="t('sections.editor.softThreshold')" :error="fieldError('softThreshold')" :supporting-text="t('sections.editor.softThresholdHint')" />
        <MdDurationField
          v-model="form.softDuration"
          :label="t('sections.editor.softDuration')"
          :error="fieldError('softDuration')"
          :supporting-text="t('sections.editor.softDurationHint')"
          :chips="SOFT_CHIPS"
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
