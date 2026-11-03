<script setup lang="ts">
// The decide child route of an appeal (`/appeals/:id/decide`). Its open state is the route: opening the address
// shows the decision dialog and sends nothing. The form and its own confirmation are the existing decision dialog.
import { computed, inject } from 'vue'
import { useRoutedDialog } from '../../router/routedDialog'
import { useSessionStore } from '../../stores/session'
import AppealDecisionDialog from './AppealDecisionDialog.vue'
import { APPEAL_CONTEXT, DECIDABLE_STATUSES } from './appealContext'

const context = inject(
  APPEAL_CONTEXT,
  () => {
    throw new Error('AppealDecideRoute is a child of the appeal page')
  },
  true,
)

const session = useSessionStore()
const dialog = useRoutedDialog({ parent: 'appeal', child: 'appeal-decide' })

const appeal = computed(() => context.appeal.value)
// A viewer, a decided appeal or a missing one never opens the dialog, whatever the address says.
const open = computed(() => {
  const current = appeal.value
  return dialog.open.value && session.moderator && current !== null && (DECIDABLE_STATUSES as readonly string[]).includes(current.status)
})

async function done(text: string): Promise<void> {
  context.announce(text)
  await dialog.close()
  await context.reload()
}
</script>

<template>
  <AppealDecisionDialog :open="open" :appeal="appeal" @close="dialog.close" @done="done" />
</template>
