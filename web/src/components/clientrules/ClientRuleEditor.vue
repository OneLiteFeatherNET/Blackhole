<script setup lang="ts">
// Creates or edits a client rule. Brand, mod and channel values are chosen by the client and can be faked,
// so the editor says so, preselects NOTIFY and asks for an explicit acknowledgement before it lets a rule
// punish on one of them. The catalogue (what the proxies saw, brands and channels from at least three
// players) only suggests: any value can be typed, also when no catalogue is known.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type {
  ClientRule,
  ClientRuleAction,
  ClientRuleCatalogue,
  ClientRuleKind,
  ClientRuleScope,
  CloudNetCatalogue,
  Section,
  ServerTargetKind,
  Template,
} from '../../api/types'
import MdButton from '../md/MdButton.vue'
import MdCheckbox from '../md/MdCheckbox.vue'
import MdDialog from '../md/MdDialog.vue'
import MdSelect from '../md/MdSelect.vue'
import MdTextField from '../md/MdTextField.vue'
import CloudNetTargetPicker from '../sections/CloudNetTargetPicker.vue'
import { clientRuleFieldErrors, type FieldErrors } from '../templates/fieldErrors'

const props = defineProps<{ open: boolean; rule: ClientRule | null }>()
const emit = defineEmits<{ close: []; saved: [message: string] }>()

const { t } = useI18n()
const api = useApi()

const KINDS = ['BRAND', 'MOD', 'CHANNEL', 'PROTOCOL', 'COMMAND'] as const
const ACTIONS = ['NOTIFY', 'DENY', 'TEMPLATE'] as const
const SUGGESTIONS_SHOWN = 30

// Number inputs are kept as text so an empty or malformed value can be told from 0.
const form = ref({
  key: '',
  displayName: '',
  kind: 'CHANNEL' as ClientRuleKind,
  enabled: true,
  patterns: '',
  protocolMin: '',
  protocolMax: '',
  negate: false,
  scope: 'GLOBAL' as ClientRuleScope,
  sectionKeys: [] as string[],
  targetKind: 'PATTERN' as ServerTargetKind,
  servers: '',
  action: 'NOTIFY' as ClientRuleAction,
  denyMessage: '',
  templateKey: '',
  acknowledgedSpoofable: false,
  hideCommand: false,
})
// Names of a CloudNet kind live apart from the pattern text, so switching back and forth loses neither.
const names = ref<string[]>([])
const picker = ref<InstanceType<typeof CloudNetTargetPicker> | null>(null)
const errors = ref<FieldErrors>({ fields: {}, general: [] })
const saving = ref(false)

const sections = ref<Section[]>([])
const templates = ref<Template[]>([])
const catalogue = ref<ClientRuleCatalogue | null>(null)
const cloudnet = ref<CloudNetCatalogue | null>(null)
const cloudnetLoading = ref(false)
let cloudnetRequested = false

const editing = computed(() => props.rule !== null)
const isProtocol = computed(() => form.value.kind === 'PROTOCOL')
const spoofable = computed(() => ['BRAND', 'MOD', 'CHANNEL'].includes(form.value.kind))
const canNegate = computed(() => form.value.kind === 'BRAND' || form.value.kind === 'PROTOCOL')
const needsAcknowledgement = computed(() => spoofable.value && form.value.action === 'TEMPLATE')
const scoped = computed(() => form.value.scope === 'SERVERS')
const cloudnetKind = computed(() => (scoped.value && form.value.targetKind !== 'PATTERN' ? form.value.targetKind : null))
const serverSections = computed(() => sections.value.filter((section) => section.enforcement === 'SERVER'))

const kindOptions = computed(() => KINDS.map((value) => ({ value, label: t(`clientRules.kind.${value}`) })))
const actionOptions = computed(() => ACTIONS.map((value) => ({ value, label: t(`clientRules.action.${value}`) })))
const scopeOptions = computed(() => (['GLOBAL', 'SERVERS'] as const).map((value) => ({ value, label: t(`clientRules.scope.${value}`) })))
const targetKindOptions = computed(() =>
  (['PATTERN', 'CLOUDNET_TASK', 'CLOUDNET_GROUP', 'CLOUDNET_SERVICE'] as const).map((value) => ({ value, label: t(`sections.targetKind.${value}`) })),
)
const templateOptions = computed(() => templates.value.map((template) => ({ value: template.key, label: `${template.key} (${template.section})` })))

const patternList = computed(() => form.value.patterns.split(',').map((pattern) => pattern.trim()).filter(Boolean))

interface Suggestion {
  name: string
  detail: string
}
const suggestions = computed<Suggestion[]>(() => {
  const source = catalogue.value
  if (!source?.available) return []
  const taken = new Set(patternList.value.map((pattern) => pattern.toLowerCase()))
  let all: Suggestion[] = []
  if (form.value.kind === 'BRAND') all = source.brands.map((entry) => ({ name: entry.name, detail: t('clientRules.editor.players', { n: entry.players }) }))
  else if (form.value.kind === 'CHANNEL') all = source.channels.map((entry) => ({ name: entry.name, detail: t('clientRules.editor.players', { n: entry.players }) }))
  else if (form.value.kind === 'COMMAND') all = source.commands.map((name) => ({ name, detail: '' }))
  return all.filter((entry) => !taken.has(entry.name.toLowerCase()))
})
const shownSuggestions = computed(() => suggestions.value.slice(0, SUGGESTIONS_SHOWN))
const suggestionsApply = computed(() => ['BRAND', 'CHANNEL', 'COMMAND'].includes(form.value.kind))
const supportedVersions = computed(() => catalogue.value?.protocols ?? [])

function addPattern(name: string): void {
  form.value.patterns = [...patternList.value, name].join(', ')
}

async function loadCloudnet(): Promise<void> {
  if (cloudnetRequested) return
  cloudnetRequested = true
  cloudnetLoading.value = true
  const result = await api.cloudnetCatalogue()
  cloudnet.value = result.ok ? result.data : null
  cloudnetLoading.value = false
}

watch(cloudnetKind, (kind) => {
  if (kind) void loadCloudnet()
})

// A task name means nothing as a group or service name, so a change between CloudNet kinds starts empty.
function chooseTargetKind(kind: string): void {
  if (kind !== form.value.targetKind && kind !== 'PATTERN' && form.value.targetKind !== 'PATTERN') names.value = []
  form.value.targetKind = kind as ServerTargetKind
}

// Changing the kind or the action withdraws the acknowledgement: it belongs to the exact combination.
function chooseKind(kind: string): void {
  if (kind !== form.value.kind) form.value.acknowledgedSpoofable = false
  form.value.kind = kind as ClientRuleKind
  if (!canNegate.value) form.value.negate = false
  if (form.value.kind !== 'COMMAND') form.value.hideCommand = false
}

function chooseAction(action: string): void {
  if (action !== form.value.action) form.value.acknowledgedSpoofable = false
  form.value.action = action as ClientRuleAction
}

function toggleSection(key: string, on: boolean): void {
  form.value.sectionKeys = on ? [...form.value.sectionKeys, key] : form.value.sectionKeys.filter((chosen) => chosen !== key)
}

watch(
  () => props.open,
  (open) => {
    if (!open) return
    const source = props.rule
    const targetKind = source?.targetKind ?? 'PATTERN'
    names.value = targetKind === 'PATTERN' ? [] : [...(source?.servers ?? [])]
    cloudnet.value = null
    cloudnetLoading.value = false
    cloudnetRequested = false
    form.value = {
      key: source?.key ?? '',
      displayName: source?.displayName ?? '',
      kind: source?.kind ?? 'CHANNEL',
      enabled: source?.enabled ?? true,
      patterns: source?.patterns.join(', ') ?? '',
      protocolMin: source?.protocolMin === null || source?.protocolMin === undefined ? '' : String(source.protocolMin),
      protocolMax: source?.protocolMax === null || source?.protocolMax === undefined ? '' : String(source.protocolMax),
      negate: source?.negate ?? false,
      scope: source?.scope ?? 'GLOBAL',
      sectionKeys: [...(source?.sectionKeys ?? [])],
      targetKind,
      servers: targetKind === 'PATTERN' ? (source?.servers.join(', ') ?? '') : '',
      // NOTIFY is the safe default: it tells the team and changes nothing for the player.
      action: source?.action ?? 'NOTIFY',
      denyMessage: source?.denyMessage ?? '',
      templateKey: source?.templateKey ?? '',
      acknowledgedSpoofable: source?.acknowledgedSpoofable ?? false,
      hideCommand: source?.hideCommand ?? false,
    }
    errors.value = { fields: {}, general: [] }
    void loadReferences()
    if (source?.scope === 'SERVERS' && targetKind !== 'PATTERN') void loadCloudnet()
  },
  { immediate: true },
)

// Sections, templates and the catalogue are suggestions and pickers: a failed load leaves the matching input free text.
async function loadReferences(): Promise<void> {
  const [loadedSections, loadedTemplates, loadedCatalogue] = await Promise.all([api.sections(), api.templates(), api.clientRuleCatalogue()])
  sections.value = loadedSections.ok ? loadedSections.data : []
  templates.value = loadedTemplates.ok ? loadedTemplates.data : []
  catalogue.value = loadedCatalogue.ok ? loadedCatalogue.data : null
}

const fieldError = (name: string) => errors.value.fields[name] ?? ''

// A message about a field is stale as soon as the form is edited.
watch(form, () => {
  if (Object.keys(errors.value.fields).length > 0) errors.value = { fields: {}, general: errors.value.general }
}, { deep: true })

const WHOLE_NUMBER = /^\d+$/

async function save(): Promise<void> {
  if (saving.value) return
  const local: Record<string, string> = {}
  if (isProtocol.value) {
    for (const name of ['protocolMin', 'protocolMax'] as const) {
      if (!WHOLE_NUMBER.test(String(form.value[name]).trim())) local[name] = t('clientRules.editor.invalidNumber')
    }
  }
  if (needsAcknowledgement.value && !form.value.acknowledgedSpoofable) local.acknowledgedSpoofable = t('clientRules.editor.acknowledgeRequired')
  if (Object.keys(local).length > 0) {
    errors.value = { fields: local, general: [] }
    return
  }

  picker.value?.commit()
  saving.value = true
  const draft = {
    key: form.value.key,
    displayName: form.value.displayName,
    kind: form.value.kind,
    enabled: form.value.enabled,
    patterns: patternList.value,
    protocolMin: isProtocol.value ? Number(form.value.protocolMin) : null,
    protocolMax: isProtocol.value ? Number(form.value.protocolMax) : null,
    negate: form.value.negate,
    scope: form.value.scope,
    sectionKeys: form.value.sectionKeys,
    targetKind: form.value.targetKind,
    servers:
      form.value.targetKind === 'PATTERN'
        ? form.value.servers.split(',').map((pattern) => pattern.trim()).filter(Boolean)
        : names.value.map((name) => name.trim()).filter(Boolean),
    action: form.value.action,
    denyMessage: form.value.denyMessage,
    templateKey: form.value.templateKey,
    acknowledgedSpoofable: form.value.acknowledgedSpoofable,
    hideCommand: form.value.hideCommand,
  }
  const result = editing.value ? await api.updateClientRule(draft) : await api.createClientRule(draft)
  saving.value = false
  if (!result.ok) {
    errors.value = clientRuleFieldErrors(result.error.message)
    if (result.error.kind === 'NETWORK') errors.value.general.push(result.error.message)
    return
  }
  emit('saved', t('clientRules.saved'))
}
</script>

<template>
  <MdDialog
    :open="open"
    wide
    :headline="rule ? t('clientRules.editor.editTitle', { key: rule.key }) : t('clientRules.editor.newTitle')"
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
          :label="t('clientRules.editor.key')"
          :error="fieldError('key')"
          :supporting-text="t('clientRules.editor.keyHint')"
        />
        <MdTextField v-model="form.displayName" required :label="t('clientRules.editor.name')" :error="fieldError('displayName')" />
      </div>

      <MdSelect
        :model-value="form.kind"
        :label="t('clientRules.editor.kind')"
        :options="kindOptions"
        :supporting-text="t(`clientRules.kindHint.${form.kind}`)"
        @update:model-value="chooseKind"
      />

      <p v-if="spoofable" role="note" data-testid="spoof-note" class="rounded-md-md bg-tertiary-container p-3 text-body-medium text-on-tertiary-container">
        {{ t('clientRules.editor.spoofNote') }}
      </p>

      <template v-if="isProtocol">
        <div class="grid gap-5 sm:grid-cols-2">
          <MdTextField v-model="form.protocolMin" type="number" required :label="t('clientRules.editor.protocolMin')" :error="fieldError('protocolMin')" :supporting-text="t('clientRules.editor.protocolHint')" />
          <MdTextField v-model="form.protocolMax" type="number" required :label="t('clientRules.editor.protocolMax')" :error="fieldError('protocolMax')" />
        </div>
        <p v-if="supportedVersions.length" class="text-body-small text-on-surface-variant" data-testid="supported-versions">
          {{ t('clientRules.editor.supportedVersions') }}
          {{ supportedVersions.map((version) => `${version.version} (${version.name})`).join(', ') }}
        </p>
      </template>
      <template v-else>
        <MdTextField
          v-model="form.patterns"
          required
          :label="t('clientRules.editor.patterns')"
          :error="fieldError('patterns')"
          :supporting-text="t(`clientRules.patternsHint.${form.kind}`)"
        />
        <div v-if="suggestionsApply" class="flex flex-col gap-2" data-testid="suggestions">
          <p v-if="!catalogue?.available" role="status" class="text-body-small text-on-surface-variant" data-testid="suggestions-missing">
            {{ t('clientRules.editor.noSuggestions') }}
          </p>
          <template v-else-if="shownSuggestions.length">
            <p class="text-label-large">{{ t('clientRules.editor.suggestions') }}</p>
            <ul class="flex flex-wrap gap-2">
              <li v-for="entry in shownSuggestions" :key="entry.name">
                <MdButton variant="tonal" :data-testid="`suggest-${entry.name}`" @click="addPattern(entry.name)">
                  {{ entry.name }}<span v-if="entry.detail" class="ml-1 text-body-small">({{ entry.detail }})</span>
                </MdButton>
              </li>
            </ul>
            <p v-if="suggestions.length > shownSuggestions.length" class="text-body-small text-on-surface-variant">
              {{ t('clientRules.editor.moreSuggestions', { shown: shownSuggestions.length, total: suggestions.length }) }}
            </p>
          </template>
        </div>
      </template>

      <MdCheckbox
        v-if="canNegate"
        v-model="form.negate"
        data-testid="negate"
        :label="t('clientRules.editor.negate')"
        :supporting-text="t(`clientRules.negateHint.${form.kind}`)"
      />
      <MdCheckbox
        v-if="form.kind === 'COMMAND'"
        v-model="form.hideCommand"
        data-testid="hide-command"
        :label="t('clientRules.editor.hideCommand')"
        :supporting-text="t('clientRules.editor.hideCommandHint')"
      />

      <fieldset class="flex flex-col gap-4 rounded-md-md border border-outline-variant p-4">
        <legend class="px-2 text-title-small">{{ t('clientRules.editor.scope') }}</legend>
        <MdSelect v-model="form.scope" :label="t('clientRules.editor.scope')" :options="scopeOptions" :error="fieldError('scope')" />
        <template v-if="scoped">
          <p v-if="serverSections.length === 0" class="text-body-small text-on-surface-variant">{{ t('clientRules.editor.noSections') }}</p>
          <div v-else data-testid="section-picker">
            <p class="text-label-large">{{ t('clientRules.editor.sections') }}</p>
            <MdCheckbox
              v-for="section in serverSections"
              :key="section.key"
              :model-value="form.sectionKeys.includes(section.key)"
              :data-testid="`section-${section.key}`"
              :label="section.key"
              :supporting-text="section.displayName"
              @update:model-value="(on: boolean) => toggleSection(section.key, on)"
            />
          </div>
          <p v-if="fieldError('sectionKeys')" role="alert" class="text-body-small text-error">{{ fieldError('sectionKeys') }}</p>
          <MdSelect
            :model-value="form.targetKind"
            :label="t('clientRules.editor.targetKind')"
            :options="targetKindOptions"
            :supporting-text="t('clientRules.editor.targetKindHint')"
            @update:model-value="chooseTargetKind"
          />
          <MdTextField
            v-if="!cloudnetKind"
            v-model="form.servers"
            :label="t('clientRules.editor.servers')"
            :error="fieldError('servers')"
            :supporting-text="t('clientRules.editor.serversHint')"
          />
          <CloudNetTargetPicker
            v-else
            ref="picker"
            v-model="names"
            :kind="cloudnetKind"
            :catalogue="cloudnet"
            :loading="cloudnetLoading"
            :error="fieldError('servers')"
          />
        </template>
      </fieldset>

      <fieldset class="flex flex-col gap-4 rounded-md-md border border-outline-variant p-4">
        <legend class="px-2 text-title-small">{{ t('clientRules.editor.action') }}</legend>
        <MdSelect
          :model-value="form.action"
          :label="t('clientRules.editor.action')"
          :options="actionOptions"
          :supporting-text="t(`clientRules.actionHint.${form.action}`)"
          @update:model-value="chooseAction"
        />
        <MdTextField
          v-if="form.action === 'DENY'"
          v-model="form.denyMessage"
          :label="t('clientRules.editor.denyMessage')"
          :error="fieldError('denyMessage')"
          :supporting-text="t('clientRules.editor.denyMessageHint')"
        />
        <template v-if="form.action === 'TEMPLATE'">
          <MdSelect
            v-model="form.templateKey"
            required
            :label="t('clientRules.editor.template')"
            :options="templateOptions"
            :error="fieldError('templateKey')"
            :supporting-text="t('clientRules.editor.templateHint')"
          />
          <div v-if="needsAcknowledgement" class="flex flex-col gap-2 rounded-md-md bg-error-container p-3 text-on-error-container" data-testid="spoof-warning" role="note">
            <p>{{ t('clientRules.editor.punishWarning') }}</p>
            <MdCheckbox v-model="form.acknowledgedSpoofable" data-testid="ack" :label="t('clientRules.editor.acknowledge')" />
            <p v-if="fieldError('acknowledgedSpoofable')" role="alert" class="text-body-small">{{ fieldError('acknowledgedSpoofable') }}</p>
          </div>
        </template>
      </fieldset>

      <MdCheckbox v-if="editing" v-model="form.enabled" :label="t('clientRules.editor.enabled')" :supporting-text="t('clientRules.editor.enabledHint')" />
    </form>
    <template #actions>
      <MdButton variant="text" data-testid="cancel" @click="emit('close')">{{ t('actions.cancel') }}</MdButton>
      <MdButton data-testid="save" :disabled="saving" @click="save">{{ t('clientRules.editor.save') }}</MdButton>
    </template>
  </MdDialog>
</template>
