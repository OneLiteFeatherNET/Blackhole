<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { Section, Template } from '../../api/types'
import { describeDuration, parseDuration } from '../../duration'
import MdButton from '../md/MdButton.vue'
import MdDialog from '../md/MdDialog.vue'
import MdDurationField from '../md/MdDurationField.vue'
import MdIconButton from '../md/MdIconButton.vue'
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

const STEP_CHIPS = ['5m', '1h', '1d', '7d', '30d']
const WINDOW_CHIPS = ['7d', '30d', '90d']

const key = ref('')
const reason = ref('')
const section = ref('')
const escalationGroup = ref('')
const escalationWindow = ref('')
const steps = ref<StepRow[]>([])
const errors = ref<FieldErrors>({ fields: {}, general: [] })
const saving = ref(false)
const advancedOpen = ref(false)
const advancedId = useId()

// Disabled sections stay in the list, marked as unavailable, so it is clear why they cannot be
// chosen. The section of the template being edited stays choosable even if it was disabled since.
const sectionOptions = computed(() =>
  props.sections.map((entry) => {
    const unavailable = !entry.enabled
    const current = entry.key === props.template?.section
    return {
      value: entry.key,
      label: entry.displayName === entry.key ? entry.key : `${entry.displayName} (${entry.key})`,
      disabled: unavailable && !current,
      description: unavailable ? t('templates.editor.sectionDisabled') : undefined,
    }
  }),
)

// Every time the dialog opens it starts from the template being edited, or from an empty form.
watch(
  () => props.open,
  (open) => {
    if (!open) return
    const source = props.template
    key.value = source?.key ?? ''
    reason.value = source?.reason ?? ''
    section.value = source?.section ?? sectionOptions.value.find((option) => !option.disabled)?.value ?? ''
    escalationGroup.value = source?.escalationGroup ?? ''
    escalationWindow.value = source?.escalationWindow ?? ''
    steps.value = source ? source.steps.map((step) => newRow(step.duration, String(step.eloCost))) : [newRow()]
    errors.value = { fields: {}, general: [] }
    // The advanced settings are rarely used: open them only when there is something in them.
    advancedOpen.value = Boolean(source?.escalationGroup || source?.escalationWindow)
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

/** What the steps mean in words: "Offence 1: 5 minutes, -50 ELO" for every step that is filled in. */
const summary = computed(() =>
  steps.value.flatMap((step, index) => {
    const duration = parseDuration(step.duration)
    const cost = String(step.eloCost).trim()
    if (!duration.ok || !WHOLE_NUMBER.test(cost)) return []
    return [
      t('templates.editor.summaryStep', {
        n: index + 1,
        duration: describeDuration(duration.seconds, locale.value),
        cost: Number(cost),
      }),
    ]
  }),
)

const durationError = (value: string): string => {
  const parsed = parseDuration(value)
  return parsed.ok ? '' : t(`duration.errors.${parsed.reason}`)
}

async function save(): Promise<void> {
  if (saving.value) return
  // Cost and durations are checked here so the messages are in the UI language; the backend still
  // validates everything else (and the same rules again).
  const local: Record<string, string> = {}
  steps.value.forEach((step, index) => {
    if (!WHOLE_NUMBER.test(String(step.eloCost).trim())) local[`steps.${index}.eloCost`] = t('templates.editor.invalidCost')
    const problem = durationError(step.duration)
    if (problem) local[`steps.${index}.duration`] = problem
  })
  if (escalationWindow.value.trim() !== '') {
    const problem = durationError(escalationWindow.value)
    if (problem) local.escalationWindow = problem
  }
  if (Object.keys(local).length > 0) {
    errors.value = { fields: local, general: [] }
    if (local.escalationWindow) advancedOpen.value = true
    return
  }

  saving.value = true
  const draft = {
    key: key.value,
    reason: reason.value,
    section: section.value,
    escalationGroup: escalationGroup.value.trim() || null,
    escalationWindow: escalationWindow.value.trim() ? (parseDuration(escalationWindow.value) as { iso: string }).iso : null,
    steps: steps.value.map((step) => ({
      duration: (parseDuration(step.duration) as { iso: string }).iso,
      eloCost: Number(String(step.eloCost).trim()),
    })),
  }
  const result = props.template
    ? await api.updateTemplate(props.template.identifier, draft)
    : await api.createTemplate(draft)
  saving.value = false
  if (!result.ok) {
    errors.value = templateFieldErrors(result.error.message)
    if (errors.value.fields.escalationGroup || errors.value.fields.escalationWindow) advancedOpen.value = true
    if (result.error.kind === 'NETWORK') errors.value.general.push(result.error.message)
    return
  }
  emit('saved', t('templates.saved'))
}

const ICON = {
  up: 'M7.41 15.41 12 10.83l4.59 4.58L18 14l-6-6-6 6z',
  down: 'M7.41 8.59 12 13.17l4.59-4.58L18 10l-6 6-6-6z',
  remove: 'M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z',
}
</script>

<template>
  <MdDialog
    :open="open"
    wide
    :headline="template ? t('templates.editor.editTitle', { key: template.key }) : t('templates.editor.newTitle')"
    @close="emit('close')"
  >
    <form class="flex flex-col gap-6 pt-2" novalidate @submit.prevent="save">
      <div v-if="errors.general.length" data-testid="error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">
        <p v-for="message in errors.general" :key="message">{{ message }}</p>
      </div>

      <MdSelect
        v-model="section"
        :label="t('templates.editor.section')"
        :options="sectionOptions"
        :error="fieldError('section')"
        :supporting-text="t('templates.editor.sectionHint')"
      />
      <div class="grid gap-6 sm:grid-cols-2">
        <MdTextField v-model="key" required :label="t('templates.editor.key')" :error="fieldError('key')" :supporting-text="t('templates.editor.keyHint')" />
        <MdTextField v-model="reason" required :label="t('templates.editor.reason')" :error="fieldError('reason')" :supporting-text="t('templates.editor.reasonHint')" />
      </div>

      <fieldset class="flex flex-col gap-4 rounded-md-md border border-outline-variant p-4">
        <legend class="px-2 text-title-small text-on-surface">{{ t('templates.editor.steps') }}</legend>
        <p class="text-body-small text-on-surface-variant">{{ t('templates.editor.stepsHint') }}</p>
        <p v-if="fieldError('steps')" role="alert" class="text-body-small text-error">{{ fieldError('steps') }}</p>

        <ol class="flex flex-col gap-3">
          <li
            v-for="(step, index) in steps"
            :key="step.id"
            class="flex flex-wrap items-start gap-x-3 gap-y-2 rounded-md-md bg-surface-container p-3"
          >
            <span
              class="order-1 mt-1 grid h-8 w-8 shrink-0 place-items-center rounded-md-full bg-secondary-container text-label-large text-on-secondary-container sm:mt-3"
            >
              {{ index + 1 }}<span class="sr-only"> {{ t('templates.editor.step', { n: index + 1 }) }}</span>
            </span>
            <!-- On a narrow screen the buttons sit in the row of the number, above the fields. -->
            <div class="order-2 ml-auto flex sm:order-4 sm:ml-0 sm:mt-2">
              <MdIconButton :label="t('templates.editor.moveUp', { n: index + 1 })" :path="ICON.up" :disabled="index === 0" @click="move(index, -1)" />
              <MdIconButton :label="t('templates.editor.moveDown', { n: index + 1 })" :path="ICON.down" :disabled="index === steps.length - 1" @click="move(index, 1)" />
              <MdIconButton :label="t('templates.editor.remove', { n: index + 1 })" :path="ICON.remove" :disabled="steps.length === 1" @click="remove(index)" />
            </div>
            <MdDurationField
              v-model="step.duration"
              class="order-3 basis-full sm:min-w-56 sm:flex-1 sm:basis-0"
              required
              :label="t('templates.editor.duration', { n: index + 1 })"
              :error="fieldError(`steps.${index}.duration`)"
              :chips="STEP_CHIPS"
            />
            <MdTextField
              v-model="step.eloCost"
              class="order-3 basis-full sm:w-40 sm:basis-auto"
              type="number"
              required
              :label="t('templates.editor.cost', { n: index + 1 })"
              :error="fieldError(`steps.${index}.eloCost`)"
              :supporting-text="t('templates.editor.costHint')"
            />
          </li>
        </ol>
        <div>
          <MdButton variant="tonal" data-testid="add-step" @click="addStep">{{ t('templates.editor.addStep') }}</MdButton>
        </div>

        <div v-if="summary.length" data-testid="summary" class="rounded-md-md bg-secondary-container/40 p-3 text-body-medium text-on-surface">
          <p class="text-label-large">{{ t('templates.editor.summaryTitle') }}</p>
          <ul class="mt-1 flex flex-col gap-0.5">
            <li v-for="line in summary" :key="line">{{ line }}</li>
          </ul>
          <p class="mt-1 text-body-small text-on-surface-variant">{{ t('templates.editor.summaryRepeat') }}</p>
        </div>
      </fieldset>

      <section class="rounded-md-md border border-outline-variant">
        <h3 class="m-0">
          <button
            type="button"
            data-testid="advanced-toggle"
            :aria-expanded="advancedOpen ? 'true' : 'false'"
            :aria-controls="advancedId"
            class="md-state flex w-full items-center gap-3 rounded-md-md px-4 py-3 text-left text-title-small text-on-surface"
            @click="advancedOpen = !advancedOpen"
          >
            <span class="flex-1">{{ t('templates.editor.advanced') }}</span>
            <svg
              viewBox="0 0 24 24"
              class="h-6 w-6 text-on-surface-variant transition-transform duration-150"
              :class="{ 'rotate-180': advancedOpen }"
              aria-hidden="true"
            >
              <path d="M7 10l5 5 5-5z" fill="currentColor" />
            </svg>
          </button>
        </h3>
        <div v-show="advancedOpen" :id="advancedId" class="flex flex-col gap-5 px-4 pb-4">
          <p class="text-body-small text-on-surface-variant">{{ t('templates.editor.advancedHint') }}</p>
          <div class="grid gap-6 sm:grid-cols-2">
            <MdTextField
              v-model="escalationGroup"
              :label="t('templates.editor.group')"
              :error="fieldError('escalationGroup')"
              :supporting-text="t('templates.editor.groupHint')"
            />
            <MdDurationField
              v-model="escalationWindow"
              :label="t('templates.editor.window')"
              :error="fieldError('escalationWindow')"
              :supporting-text="t('templates.editor.windowHint')"
              :chips="WINDOW_CHIPS"
            />
          </div>
        </div>
      </section>
    </form>
    <template #actions>
      <MdButton variant="text" data-testid="cancel" @click="emit('close')">{{ t('actions.cancel') }}</MdButton>
      <MdButton data-testid="save" :disabled="saving" @click="save">{{ t('templates.editor.save') }}</MdButton>
    </template>
  </MdDialog>
</template>
