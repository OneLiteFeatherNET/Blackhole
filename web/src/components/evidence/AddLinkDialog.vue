<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import MdButton from '../md/MdButton.vue'
import MdDialog from '../md/MdDialog.vue'
import MdSelect from '../md/MdSelect.vue'
import MdTextField from '../md/MdTextField.vue'
import { safeHttpUrl } from './safeLink'

const props = defineProps<{ open: boolean; punishment: string }>()
const emit = defineEmits<{ close: []; done: [message: string] }>()

const { t } = useI18n()
const api = useApi()

const kind = ref<'LINK' | 'REPLAY'>('LINK')
const url = ref('')
const description = ref('')
const urlError = ref('')
const error = ref('')
const saving = ref(false)

const kinds = computed(() => (['LINK', 'REPLAY'] as const).map((value) => ({ value, label: t(`evidence.kind.${value}`) })))

watch(
  () => props.open,
  (open) => {
    if (!open) return
    kind.value = 'LINK'
    url.value = ''
    description.value = ''
    urlError.value = ''
    error.value = ''
  },
  { immediate: true },
)

async function confirm(): Promise<void> {
  if (saving.value) return
  urlError.value = ''
  error.value = ''
  // The backend validates again; this keeps javascript:, data: and relative values from ever being sent.
  if (!safeHttpUrl(url.value)) {
    urlError.value = t('evidence.addLink.invalidUrl')
    return
  }
  saving.value = true
  const result = await api.addLinkEvidence(props.punishment, { kind: kind.value, url: url.value, description: description.value })
  saving.value = false
  if (!result.ok) {
    error.value = result.error.message
    return
  }
  emit('done', t('evidence.addLink.done'))
}
</script>

<template>
  <MdDialog :open="open" :headline="t('evidence.addLink.title')" @close="emit('close')">
    <form class="flex flex-col gap-5 pt-2" novalidate @submit.prevent="confirm">
      <MdSelect v-model="kind" required :label="t('evidence.addLink.kind')" :options="kinds" />
      <MdTextField
        v-model="url"
        type="url"
        required
        :label="t('evidence.addLink.url')"
        :error="urlError"
        :supporting-text="t('evidence.addLink.urlHint')"
      />
      <MdTextField v-model="description" :label="t('evidence.addLink.description')" :supporting-text="t('evidence.addLink.descriptionHint')" />
      <p v-if="error" data-testid="error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">{{ error }}</p>
      <!-- Lets Enter in a field submit the form without a visible extra button. -->
      <button type="submit" class="hidden" tabindex="-1" aria-hidden="true" />
    </form>
    <template #actions>
      <MdButton variant="text" data-testid="cancel" @click="emit('close')">{{ t('actions.cancel') }}</MdButton>
      <MdButton data-testid="confirm" :disabled="saving" @click="confirm">{{ t('evidence.addLink.confirm') }}</MdButton>
    </template>
  </MdDialog>
</template>
