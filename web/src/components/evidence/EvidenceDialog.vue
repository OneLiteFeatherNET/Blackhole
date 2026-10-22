<script setup lang="ts">
// The evidence panel of one punishment in a dialog. The panel only exists while the dialog is open,
// so each opening starts fresh: first page, lifted evidence hidden.
import { useI18n } from 'vue-i18n'
import MdButton from '../md/MdButton.vue'
import MdDialog from '../md/MdDialog.vue'
import EvidencePanel from './EvidencePanel.vue'

defineProps<{ open: boolean; punishment: string }>()
const emit = defineEmits<{ close: [] }>()

const { t } = useI18n()
</script>

<template>
  <MdDialog :open="open" wide :headline="t('evidence.dialogTitle')" @close="emit('close')">
    <EvidencePanel :punishment="punishment" />
    <template #actions>
      <MdButton variant="text" data-testid="close-evidence" @click="emit('close')">{{ t('evidence.close') }}</MdButton>
    </template>
  </MdDialog>
</template>
