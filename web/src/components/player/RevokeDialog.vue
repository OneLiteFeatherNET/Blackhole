<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import MdButton from '../md/MdButton.vue'
import MdDialog from '../md/MdDialog.vue'

const props = defineProps<{
  open: boolean
  owner: string
  section: string
  /** Whether the active punishment is permanent; revoking it pardons the player and resets the score. */
  permanent: boolean
  pardonScore: number
}>()
const emit = defineEmits<{ close: []; done: [message: string] }>()

const { t } = useI18n()
const api = useApi()

const revoking = ref(false)
const error = ref('')

watch(
  () => props.open,
  (open) => {
    if (open) error.value = ''
  },
)

async function confirm(): Promise<void> {
  if (revoking.value) return
  revoking.value = true
  error.value = ''
  const result = await api.revoke(props.owner, props.section)
  revoking.value = false
  if (!result.ok) {
    error.value = result.error.message
    return
  }
  const reset = result.data.scoreReset
  emit(
    'done',
    reset === null || reset === undefined
      ? t('actions.revoke.done')
      : t('actions.revoke.doneReset', { section: props.section, score: reset }),
  )
}
</script>

<template>
  <MdDialog :open="open" :headline="t('actions.revoke.title', { section })" @close="emit('close')">
    <div class="flex flex-col gap-3">
      <p>{{ t('actions.revoke.body', { section }) }}</p>
      <p v-if="permanent" data-testid="reset-note" class="rounded-md-md bg-tertiary-container p-3 text-on-tertiary-container">
        {{ t('actions.revoke.resets', { section, pardon: pardonScore }) }}
      </p>
      <p v-if="error" data-testid="error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">
        {{ error }}
      </p>
    </div>
    <template #actions>
      <MdButton variant="text" data-testid="cancel" @click="emit('close')">{{ t('actions.cancel') }}</MdButton>
      <MdButton data-testid="confirm" :disabled="revoking" @click="confirm">{{ t('actions.revoke.confirm') }}</MdButton>
    </template>
  </MdDialog>
</template>
