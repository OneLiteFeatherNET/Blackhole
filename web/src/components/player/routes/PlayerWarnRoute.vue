<script setup lang="ts">
// Warn a player, behind its child route `/players/:owner/warn`. Opening the route sends nothing; the warning is sent
// by the dialog's own confirmation. The dialog stays open after a warning to show the step, so closing it is the
// only way back to the player page.
import { useRoutedDialog } from '../../../router/routedDialog'
import WarnDialog from '../../warning/WarnDialog.vue'
import { requirePlayerContext } from '../playerContext'

const props = defineProps<{ owner: string }>()

const context = requirePlayerContext('PlayerWarnRoute')
const dialog = useRoutedDialog({ parent: 'player', child: 'player-warn' })
</script>

<template>
  <WarnDialog :open="true" :owner="props.owner" @close="dialog.close" @done="context.warned" />
</template>
