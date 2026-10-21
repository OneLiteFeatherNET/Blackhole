<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { Report, ReportStatus, Template } from '../../api/types'
import { shortHash } from '../../format'
import MdButton from '../md/MdButton.vue'
import MdDialog from '../md/MdDialog.vue'
import MdSelect from '../md/MdSelect.vue'
import MdTextField from '../md/MdTextField.vue'

const props = defineProps<{ open: boolean; report: Report | null }>()
const emit = defineEmits<{ close: []; done: [message: string] }>()

const { t } = useI18n()
const api = useApi()

const decision = ref('')
const note = ref('')
const templateId = ref('')
const templates = ref<Template[]>([])
const error = ref('')
const saving = ref(false)

const decisionOptions = computed(() => [
  { value: '', label: t('reports.resolve.choose') },
  ...(['ACTIONED', 'DISMISSED', 'UNDER_REVIEW'] as const).map((value) => ({
    value,
    label: t(`reports.resolve.decision.${value}`),
  })),
])
const templateOptions = computed(() => [
  { value: '', label: t('reports.resolve.noPunishment') },
  ...templates.value.map((template) => ({
    value: template.identifier,
    label: `${template.key} - ${template.reason} (${template.section})`,
  })),
])

watch(
  () => props.open,
  async (open) => {
    if (!open) return
    decision.value = ''
    note.value = ''
    templateId.value = ''
    error.value = ''
    const result = await api.templates()
    if (result.ok) templates.value = result.data
    else error.value = result.error.message
  },
  { immediate: true },
)

async function confirm(): Promise<void> {
  if (!props.report?.identifier || !decision.value || saving.value) return
  saving.value = true
  error.value = ''
  const actioned = decision.value === 'ACTIONED'
  const result = await api.resolveReport(props.report.identifier, {
    status: decision.value as ReportStatus,
    note: note.value.trim() || undefined,
    // A template only goes with an actioned report; the backend rejects it otherwise.
    templateId: actioned && templateId.value ? templateId.value : undefined,
  })
  saving.value = false
  if (!result.ok) {
    error.value = result.error.message
    return
  }
  emit('done', t('reports.resolved'))
}
</script>

<template>
  <MdDialog :open="open" :headline="t('reports.resolve.title')" @close="emit('close')">
    <div class="flex flex-col gap-5 pt-2">
      <p v-if="report" class="text-body-medium">
        {{ t('reports.resolve.about', { player: shortHash(report.reportedHash) }) }}
        <span v-if="report.description" class="block text-on-surface-variant">{{ report.description }}</span>
      </p>
      <MdSelect v-model="decision" :label="t('reports.resolve.decisionLabel')" :options="decisionOptions" />
      <MdSelect
        v-if="decision === 'ACTIONED'"
        v-model="templateId"
        :label="t('reports.resolve.template')"
        :options="templateOptions"
      />
      <p v-if="decision === 'ACTIONED' && templateId" class="-mt-3 text-body-small text-on-surface-variant">
        {{ t('reports.resolve.templateHint') }}
      </p>
      <MdTextField v-model="note" :label="t('reports.resolve.note')" :supporting-text="t('reports.resolve.noteHint')" />
      <p v-if="error" data-testid="error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">
        {{ error }}
      </p>
    </div>
    <template #actions>
      <MdButton variant="text" data-testid="cancel" @click="emit('close')">{{ t('actions.cancel') }}</MdButton>
      <MdButton data-testid="confirm" :disabled="!decision || saving" @click="confirm">{{ t('reports.resolve.confirm') }}</MdButton>
    </template>
  </MdDialog>
</template>
