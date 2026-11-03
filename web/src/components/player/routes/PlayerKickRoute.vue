<script setup lang="ts">
// Kick a player from the network, behind its child route `/players/:owner/kick`. Opening the route sends nothing: the
// kick is sent by the dialog's own confirmation, and the dialog shows the outcome it reaches.
import { useRoutedDialog } from '../../../router/routedDialog'
import { useLookupStore } from '../../../stores/lookup'
import KickDialog from '../../kick/KickDialog.vue'
import { requirePlayerContext } from '../playerContext'

const props = defineProps<{ owner: string }>()

const context = requirePlayerContext('PlayerKickRoute')
const lookup = useLookupStore()
const dialog = useRoutedDialog({ parent: 'player', child: 'player-kick' })
</script>

<template>
  <KickDialog :open="true" :owner="props.owner" :name="lookup.nameOf(props.owner) ?? ''" @close="dialog.close" @done="context.reload" />
</template>
