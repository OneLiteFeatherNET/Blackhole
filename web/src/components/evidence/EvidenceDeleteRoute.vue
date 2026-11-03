<script setup lang="ts">
// `/evidence/:id/delete`: the delete confirmation of an evidence item (ADMIN only). The address opens the form only; the
// item is deleted when the form is confirmed, never on navigation. After a delete the page of its case is shown.
import { inject } from 'vue'
import { useRouter } from 'vue-router'
import { useRoutedDialog } from '../../router/routedDialog'
import DeleteEvidenceDialog from './DeleteEvidenceDialog.vue'
import { EVIDENCE_ITEM_KEY } from './evidenceItemContext'

const router = useRouter()
const context = inject(EVIDENCE_ITEM_KEY)!
const dialog = useRoutedDialog({ parent: 'evidence-item', child: 'evidence-delete' })

async function done() {
  const caseReference = context.item.value?.punishmentIdentifier ?? null
  await router.replace(caseReference ? { name: 'case', params: { reference: caseReference } } : { name: 'cases' })
}
</script>

<template>
  <DeleteEvidenceDialog :open="dialog.open.value" :item="context.item.value" @close="dialog.close" @done="done" />
</template>
