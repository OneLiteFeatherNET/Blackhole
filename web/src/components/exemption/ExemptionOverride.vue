<script setup lang="ts">
// Shown inside the apply and kick dialogs after the backend refused a protected target (TARGET_EXEMPT). Every role sees
// the refusal; only an ADMIN sees the override, which needs a justification and is recorded on the punishment.
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import MdButton from '../md/MdButton.vue'
import MdTextField from '../md/MdTextField.vue'

const props = withDefaults(defineProps<{ admin: boolean; message: string; busy?: boolean }>(), { busy: false })
const emit = defineEmits<{ override: [justification: string] }>()

// The backend allows 1 to 500 characters; the field enforces the same bounds before a request is made.
const MAX_JUSTIFICATION = 500

const { t } = useI18n()
const justification = ref('')
const problem = ref('')

function confirm(): void {
  if (props.busy) return
  const text = justification.value.trim()
  if (text === '') {
    problem.value = t('exemption.override.empty')
    return
  }
  if (text.length > MAX_JUSTIFICATION) {
    problem.value = t('exemption.override.tooLong')
    return
  }
  problem.value = ''
  emit('override', text)
}
</script>

<template>
  <section class="flex flex-col gap-3 rounded-md-md bg-error-container p-3 text-on-error-container" aria-labelledby="exemption-refusal-title">
    <h3 id="exemption-refusal-title" class="text-title-small">{{ t('exemption.refused.title') }}</h3>
    <p role="alert" data-testid="exemption-refusal">{{ message }}</p>
    <p v-if="!admin" data-testid="exemption-moderator-hint">{{ t('exemption.refused.moderator') }}</p>

    <template v-if="admin">
      <p data-testid="exemption-admin-hint">{{ t('exemption.refused.admin') }}</p>
      <MdTextField
        v-model="justification"
        :label="t('exemption.override.label')"
        :error="problem"
        :supporting-text="t('exemption.override.hint')"
        :disabled="busy"
        required
        data-testid="exemption-justification"
      />
      <div class="flex justify-end">
        <MdButton variant="error" data-testid="exemption-override" :disabled="busy" @click="confirm">
          {{ t('exemption.override.submit') }}
        </MdButton>
      </div>
    </template>
  </section>
</template>
