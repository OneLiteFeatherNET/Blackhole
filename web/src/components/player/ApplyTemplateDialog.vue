<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { ApplyPreview, EnforcementKind, Report, ReportCategory, Section, Template } from '../../api/types'
import { formatDuration } from '../../format'
import MdButton from '../md/MdButton.vue'
import MdCheckbox from '../md/MdCheckbox.vue'
import MdDialog from '../md/MdDialog.vue'
import MdProgress from '../md/MdProgress.vue'
import MdSelect from '../md/MdSelect.vue'

// With `report` the dialog resolves that report as actioned with the chosen template (one call to the
// resolve route, which applies the template and links it to the report) instead of applying it alone,
// and offers the templates whose section fits the report's category first.
const props = defineProps<{ open: boolean; owner: string; playerUuid?: string | null; report?: Report }>()
const emit = defineEmits<{ close: []; done: [message: string] }>()

const { t, locale } = useI18n()
const api = useApi()

const templates = ref<Template[]>([])
const selected = ref('')
const preview = ref<ApplyPreview | null>(null)
const previewing = ref(false)
const applying = ref(false)
const error = ref('')
const sections = ref<Section[]>([])
const showAll = ref(false)

/** The kinds of section a report category is usually punished in; undefined means every kind fits. */
const FITTING: Partial<Record<ReportCategory, EnforcementKind[]>> = {
  CHAT_ABUSE: ['MUTE'],
  CHEATING: ['BAN', 'SERVER'],
  GRIEFING: ['BAN', 'SERVER'],
}

const fitting = computed(() => {
  const kinds = props.report ? FITTING[props.report.category] : undefined
  if (!kinds || showAll.value) return templates.value
  const sectionKeys = new Set(sections.value.filter((section) => kinds.includes(section.enforcement)).map((section) => section.key))
  return templates.value.filter((template) => sectionKeys.has(template.section))
})
const filtered = computed(() => props.report !== undefined && fitting.value.length < templates.value.length)

const options = computed(() => [
  ...fitting.value.map((template) => ({
    value: template.key,
    label: template.key,
    description: `${template.reason} \u00b7 ${template.section}`,
  })),
])

watch(
  () => props.open,
  async (open) => {
    if (!open) return
    selected.value = ''
    preview.value = null
    error.value = ''
    showAll.value = false
    const result = await api.templates()
    if (result.ok) templates.value = result.data
    else error.value = result.error.message
    if (props.report) {
      const known = await api.sections()
      if (known.ok) sections.value = known.data
    }
  },
  { immediate: true },
)

watch(selected, async (key) => {
  preview.value = null
  error.value = ''
  if (!key) return
  previewing.value = true
  const result = await api.previewApply(props.owner, key)
  if (key !== selected.value) return // another template was chosen meanwhile
  previewing.value = false
  if (result.ok) preview.value = result.data
  else error.value = result.error.message
})

async function confirm(): Promise<void> {
  if (!preview.value || applying.value) return
  applying.value = true
  error.value = ''
  if (props.report?.identifier) {
    const template = templates.value.find((entry) => entry.key === selected.value)
    const resolved = await api.resolveReport(props.report.identifier, { status: 'ACTIONED', templateId: template?.identifier })
    applying.value = false
    if (!resolved.ok) {
      error.value = resolved.error.message
      return
    }
    emit('done', t('reports.detail.actioned', { section: preview.value.section }))
    return
  }
  const result = await api.applyTemplate(props.owner, selected.value, props.playerUuid ?? null)
  applying.value = false
  if (!result.ok) {
    error.value = result.error.message
    return
  }
  const applied = result.data
  emit(
    'done',
    applied.escalation
      ? t('actions.apply.doneEscalated', { section: applied.section })
      : t('actions.apply.done', { step: applied.step, count: applied.stepCount, after: applied.scoreAfter }),
  )
}
</script>

<template>
  <MdDialog :open="open" :headline="t('actions.apply.title')" @close="emit('close')">
    <div class="flex min-h-64 flex-col gap-4">
      <MdCheckbox
        v-if="filtered || (report && showAll)"
        v-model="showAll"
        data-testid="show-all-templates"
        :label="t('reports.detail.allTemplates')"
        :supporting-text="t('reports.detail.allTemplatesHint')"
      />
      <MdSelect v-model="selected" required :placeholder="t('actions.apply.choose')" :label="t('actions.apply.template')" :options="options" />
      <MdProgress v-if="previewing" :label="t('actions.apply.loadingPreview')" />
      <div v-if="preview" data-testid="preview" class="flex flex-col gap-1 text-on-surface">
        <p class="text-title-small">{{ t('actions.apply.step', { step: preview.step, count: preview.stepCount }) }}</p>
        <p>
          {{ t('actions.apply.stepDetail', { duration: formatDuration(preview.duration, locale), cost: preview.eloCost }) }}
        </p>
        <p>
          {{ t('actions.apply.score', { section: preview.section, before: preview.scoreBefore, after: preview.scoreAfter }) }}
        </p>
      </div>
      <p
        v-if="preview?.escalatesToPermanent"
        data-testid="permanent-warning"
        role="alert"
        class="rounded-md-md bg-error-container p-3 text-on-error-container"
      >
        {{
          t('actions.apply.warning', {
            section: preview.section,
            after: preview.scoreAfter,
            threshold: preview.permanentThreshold,
          })
        }}
      </p>
      <p v-if="error" data-testid="error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">
        {{ error }}
      </p>
    </div>
    <template #actions>
      <MdButton variant="text" data-testid="cancel" @click="emit('close')">{{ t('actions.cancel') }}</MdButton>
      <MdButton data-testid="confirm" :disabled="!preview || applying" @click="confirm">
        {{ t('actions.apply.confirm') }}
      </MdButton>
    </template>
  </MdDialog>
</template>
