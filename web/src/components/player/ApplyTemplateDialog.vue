<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { ApplyPreview, Template } from '../../api/types'
import { formatDuration } from '../../format'
import MdButton from '../md/MdButton.vue'
import MdDialog from '../md/MdDialog.vue'
import MdProgress from '../md/MdProgress.vue'
import MdSelect from '../md/MdSelect.vue'

const props = defineProps<{ open: boolean; owner: string }>()
const emit = defineEmits<{ close: []; done: [message: string] }>()

const { t, locale } = useI18n()
const api = useApi()

const templates = ref<Template[]>([])
const selected = ref('')
const preview = ref<ApplyPreview | null>(null)
const previewing = ref(false)
const applying = ref(false)
const error = ref('')

const options = computed(() => [
  ...templates.value.map((template) => ({
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
    const result = await api.templates()
    if (result.ok) templates.value = result.data
    else error.value = result.error.message
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
  const result = await api.applyTemplate(props.owner, selected.value)
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
