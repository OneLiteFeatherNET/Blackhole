<script setup lang="ts">
// Decides one held-back automatic punishment of a protected player: apply it with the justification recorded, or dismiss
// it. Both need a justification; the decision is sent once and the buttons stay disabled until the answer arrives.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { ExemptionReview } from '../../api/types'
import MdButton from '../md/MdButton.vue'
import MdDialog from '../md/MdDialog.vue'
import MdTextField from '../md/MdTextField.vue'

export type ExemptionDecision = 'apply' | 'dismiss'

const props = defineProps<{ open: boolean; review: ExemptionReview | null; action: ExemptionDecision | null }>()
const emit = defineEmits<{ close: []; done: [message: string] }>()

const MAX_JUSTIFICATION = 500

const { t } = useI18n()
const api = useApi()

const justification = ref('')
const problem = ref('')
const error = ref('')
const saving = ref(false)

const headline = computed(() => (props.action === 'dismiss' ? t('exemptionReviews.dismiss.title') : t('exemptionReviews.apply.title')))
const intro = computed(() => (props.action === 'dismiss' ? t('exemptionReviews.dismiss.intro') : t('exemptionReviews.apply.intro')))

watch(
  () => props.open,
  (open) => {
    if (!open) return
    justification.value = ''
    problem.value = ''
    error.value = ''
    saving.value = false
  },
)

async function confirm(): Promise<void> {
  if (!props.review || !props.action || saving.value) return
  const text = justification.value.trim()
  if (text === '') {
    problem.value = t('exemptionReviews.justification.empty')
    return
  }
  if (text.length > MAX_JUSTIFICATION) {
    problem.value = t('exemptionReviews.justification.tooLong')
    return
  }
  problem.value = ''
  saving.value = true
  error.value = ''
  const identifier = props.review.id
  const result =
    props.action === 'dismiss' ? await api.dismissExemptionReview(identifier, text) : await api.applyExemptionReview(identifier, text)
  saving.value = false
  if (!result.ok) {
    error.value = result.error.message
    return
  }
  emit('done', props.action === 'dismiss' ? t('exemptionReviews.dismissed') : t('exemptionReviews.applied'))
}
</script>

<template>
  <MdDialog :open="open" :headline="headline" @close="emit('close')">
    <div class="flex flex-col gap-4 pt-2">
      <p>{{ intro }}</p>
      <MdTextField
        v-model="justification"
        :label="t('exemptionReviews.justification.label')"
        :error="problem"
        :supporting-text="t('exemptionReviews.justification.hint')"
        :disabled="saving"
        multiline
        :rows="3"
        required
        data-testid="justification"
      />
      <p v-if="error" data-testid="error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">{{ error }}</p>
    </div>
    <template #actions>
      <MdButton variant="text" data-testid="cancel" :disabled="saving" @click="emit('close')">{{ t('actions.cancel') }}</MdButton>
      <MdButton data-testid="confirm" :disabled="saving" @click="confirm">
        {{ action === 'dismiss' ? t('exemptionReviews.dismiss.confirm') : t('exemptionReviews.apply.confirm') }}
      </MdButton>
    </template>
  </MdDialog>
</template>
