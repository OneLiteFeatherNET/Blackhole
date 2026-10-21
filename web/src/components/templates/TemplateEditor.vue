<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { Section, Template } from '../../api/types'
import { formatDuration } from '../../format'
import MdButton from '../md/MdButton.vue'
import MdDialog from '../md/MdDialog.vue'
import MdSelect from '../md/MdSelect.vue'
import MdTextField from '../md/MdTextField.vue'
import { templateFieldErrors, type FieldErrors } from './fieldErrors'

const props = defineProps<{ open: boolean; template: Template | null; sections: Section[] }>()
const emit = defineEmits<{ close: []; saved: [message: string] }>()

const { t, locale } = useI18n()
const api = useApi()

interface StepRow {
  /** Stable identity so a field keeps its focus and value when rows move. */
  id: number
  duration: string
  eloCost: string
}

let nextId = 0
const newRow = (duration = '', eloCost = '0'): StepRow => ({ id: nextId++, duration, eloCost })

const key = ref('')
const reason = ref('')
const section = ref('')
const escalationGroup = ref('')
const escalationWindow = ref('')
const steps = ref<StepRow[]>([])
const errors = ref<FieldErrors>({ fields: {}, general: [] })
const saving = ref(false)

const sectionOptions = computed(() =>
  props.sections.filter((entry) => entry.enabled || entry.key === props.template?.section).map((entry) => ({
    value: entry.key,
    label: entry.displayName === entry.key ? entry.key : `${entry.displayName} (${entry.key})`,
  })),
)

// Every time the dialog opens it starts from the template being edited, or from an empty form.
watch(
  () => props.open,
  (open) => {
    if (!open) return
    const source = props.template
    key.value = source?.key ?? ''
    reason.value = source?.reason ?? ''
    section.value = source?.section ?? sectionOptions.value[0]?.value ?? ''
    escalationGroup.value = source?.escalationGroup ?? ''
    escalationWindow.value = source?.escalationWindow ?? ''
    steps.value = source ? source.steps.map((step) => newRow(step.duration, String(step.eloCost))) : [newRow()]
    errors.value = { fields: {}, general: [] }
  },
  { immediate: true },
)

function move(index: number, by: -1 | 1): void {
  const target = index + by
  if (target < 0 || target >= steps.value.length) return
  const next = [...steps.value]
  ;[next[index], next[target]] = [next[target]!, next[index]!]
  steps.value = next
}

const remove = (index: number) => (steps.value = steps.value.filter((_, i) => i !== index))
const addStep = () => steps.value.push(newRow())

const fieldError = (name: string) => errors.value.fields[name] ?? ''

// A message about a field is stale as soon as the form is edited.
watch([key, reason, section, escalationGroup, escalationWindow, steps], () => {
  if (Object.keys(errors.value.fields).length > 0) errors.value = { fields: {}, general: errors.value.general }
}, { deep: true })
const WHOLE_NUMBER = /^\d+$/

async function save(): Promise<void> {
  if (saving.value) return
  // The cost is the only thing checked here; durations and everything else are validated by the backend.
  const local: Record<string, string> = {}
  steps.value.forEach((step, index) => {
    if (!WHOLE_NUMBER.test(String(step.eloCost).trim())) local[`steps.${index}.eloCost`] = t('templates.editor.invalidCost')
  })
  if (Object.keys(local).length > 0) {
    errors.value = { fields: local, general: [] }
    return
  }

  saving.value = true
  const draft = {
    key: key.value,
    reason: reason.value,
    section: section.value,
    escalationGroup: escalationGroup.value.trim() || null,
    escalationWindow: escalationWindow.value.trim() || null,
    steps: steps.value.map((step) => ({ duration: step.duration, eloCost: Number(String(step.eloCost).trim()) })),
  }
  const result = props.template
    ? await api.updateTemplate(props.template.identifier, draft)
    : await api.createTemplate(draft)
  saving.value = false
  if (!result.ok) {
    errors.value = templateFieldErrors(result.error.message)
    if (result.error.kind === 'NETWORK') errors.value.general.push(result.error.message)
    return
  }
  emit('saved', t('templates.saved'))
}
</script>

<template>
  <MdDialog
    :open="open"
    wide
    :headline="template ? t('templates.editor.editTitle', { key: template.key }) : t('templates.editor.newTitle')"
    @close="emit('close')"
  >
    <form class="flex flex-col gap-5 pt-2" novalidate @submit.prevent="save">
      <div v-if="errors.general.length" data-testid="error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">
        <p v-for="message in errors.general" :key="message">{{ message }}</p>
      </div>

      <div class="grid gap-5 sm:grid-cols-2">
        <MdTextField v-model="key" required :label="t('templates.editor.key')" :error="fieldError('key')" :supporting-text="t('templates.editor.keyHint')" />
        <MdSelect v-model="section" :label="t('templates.editor.section')" :options="sectionOptions" />
      </div>
      <p v-if="fieldError('section')" role="alert" class="-mt-3 px-4 text-body-small text-error">{{ fieldError('section') }}</p>
      <MdTextField v-model="reason" required :label="t('templates.editor.reason')" :error="fieldError('reason')" />
      <div class="grid gap-5 sm:grid-cols-2">
        <MdTextField
          v-model="escalationGroup"
          :label="t('templates.editor.group')"
          :error="fieldError('escalationGroup')"
          :supporting-text="t('templates.editor.groupHint')"
        />
        <MdTextField
          v-model="escalationWindow"
          :label="t('templates.editor.window')"
          :error="fieldError('escalationWindow')"
          :supporting-text="t('templates.editor.windowHint')"
        />
      </div>

      <fieldset class="flex flex-col gap-4 rounded-md-md border border-outline-variant p-4">
        <legend class="px-2 text-title-small">{{ t('templates.editor.steps') }}</legend>
        <p class="text-body-small text-on-surface-variant">{{ t('templates.editor.stepsHint') }}</p>
        <p v-if="fieldError('steps')" role="alert" class="text-body-small text-error">{{ fieldError('steps') }}</p>

        <ol class="flex flex-col gap-4">
          <li v-for="(step, index) in steps" :key="step.id" class="flex flex-wrap items-start gap-3">
            <span class="mt-4 w-6 text-title-small text-on-surface-variant" aria-hidden="true">{{ index + 1 }}</span>
            <MdTextField
              v-model="step.duration"
              class="min-w-40 flex-1"
              required
              :label="t('templates.editor.duration', { n: index + 1 })"
              :error="fieldError(`steps.${index}.duration`)"
              :supporting-text="step.duration ? formatDuration(step.duration, locale) : t('templates.editor.durationHint')"
            />
            <MdTextField
              v-model="step.eloCost"
              class="w-44"
              type="number"
              required
              :label="t('templates.editor.cost', { n: index + 1 })"
              :error="fieldError(`steps.${index}.eloCost`)"
            />
            <div class="mt-2 flex">
              <button
                type="button"
                :aria-label="t('templates.editor.moveUp', { n: index + 1 })"
                :disabled="index === 0"
                class="md-state grid h-10 w-10 place-items-center rounded-md-full text-on-surface-variant disabled:opacity-[0.38]"
                @click="move(index, -1)"
              >
                <svg viewBox="0 0 24 24" class="h-6 w-6" aria-hidden="true"><path d="M7.41 15.41 12 10.83l4.59 4.58L18 14l-6-6-6 6z" fill="currentColor" /></svg>
              </button>
              <button
                type="button"
                :aria-label="t('templates.editor.moveDown', { n: index + 1 })"
                :disabled="index === steps.length - 1"
                class="md-state grid h-10 w-10 place-items-center rounded-md-full text-on-surface-variant disabled:opacity-[0.38]"
                @click="move(index, 1)"
              >
                <svg viewBox="0 0 24 24" class="h-6 w-6" aria-hidden="true"><path d="M7.41 8.59 12 13.17l4.59-4.58L18 10l-6 6-6-6z" fill="currentColor" /></svg>
              </button>
              <button
                type="button"
                :aria-label="t('templates.editor.remove', { n: index + 1 })"
                :disabled="steps.length === 1"
                class="md-state grid h-10 w-10 place-items-center rounded-md-full text-on-surface-variant disabled:opacity-[0.38]"
                @click="remove(index)"
              >
                <svg viewBox="0 0 24 24" class="h-6 w-6" aria-hidden="true"><path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z" fill="currentColor" /></svg>
              </button>
            </div>
          </li>
        </ol>
        <div>
          <MdButton variant="tonal" data-testid="add-step" @click="addStep">{{ t('templates.editor.addStep') }}</MdButton>
        </div>
      </fieldset>
    </form>
    <template #actions>
      <MdButton variant="text" data-testid="cancel" @click="emit('close')">{{ t('actions.cancel') }}</MdButton>
      <MdButton data-testid="save" :disabled="saving" @click="save">{{ t('templates.editor.save') }}</MdButton>
    </template>
  </MdDialog>
</template>
