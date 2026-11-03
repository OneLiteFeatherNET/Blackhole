<script setup lang="ts">
// Apply a template to a player, behind its child route `/players/:owner/punish`. Opening the route sends nothing:
// the dialog's own confirmation sends the change. Closing it leaves the route and keeps the player page.
import { useRoutedDialog } from '../../../router/routedDialog'
import ApplyTemplateDialog from '../ApplyTemplateDialog.vue'
import { requirePlayerContext } from '../playerContext'

const props = defineProps<{ owner: string }>()

const context = requirePlayerContext('PlayerPunishRoute')
const dialog = useRoutedDialog({ parent: 'player', child: 'player-punish' })

async function done(message: string): Promise<void> {
  await context.changed(message)
  await dialog.close()
}
</script>

<template>
  <ApplyTemplateDialog :open="true" :owner="props.owner" :player-uuid="context.playerUuid.value" @close="dialog.close" @done="done" />
</template>
