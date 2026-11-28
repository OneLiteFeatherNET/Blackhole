<script setup lang="ts">
// Adjust the score of one section, behind its child route `/players/:owner/elo/adjust?section=<key>`. Opening the route
// sends nothing. A section the player does not have shows not-found and no form (never an empty dialog).
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoutedDialog } from '../../../router/routedDialog'
import RoutedDialog from '../../RoutedDialog.vue'
import AdjustDialog from '../AdjustDialog.vue'
import { requirePlayerContext } from '../playerContext'

const props = defineProps<{ owner: string; section: string }>()

const { t } = useI18n()
const context = requirePlayerContext('PlayerAdjustRoute')
const dialog = useRoutedDialog({ parent: 'player', child: 'player-adjust' })

const entry = computed(() => context.scores.value.find((score) => score.section === props.section) ?? null)

async function done(message: string): Promise<void> {
  await context.changed(message)
  await dialog.close()
}
</script>

<template>
  <RoutedDialog v-if="entry === null" parent="player" child="player-adjust" :headline="t('actions.adjust.open')" not-found />
  <AdjustDialog
    v-else
    :open="true"
    :owner="props.owner"
    :entry="entry"
    :has-active-permanent="context.hasActivePermanent(entry.section)"
    @close="dialog.close"
    @done="done"
  />
</template>
