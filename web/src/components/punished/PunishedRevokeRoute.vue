<script setup lang="ts">
// Revoke the active punishment of an entry, behind its child route `/punished/:shortId/revoke?owner=<hash>`. Opening
// the route reads what the confirmation needs and sends nothing; the revoke is sent by the dialog's own confirmation.
// The address names only the short id and the owner hash, so the entry is found again from the short id.
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { Punishment } from '../../api/types'
import { useRoutedDialog } from '../../router/routedDialog'
import RoutedDialog from '../RoutedDialog.vue'
import RevokeDialog from '../player/RevokeDialog.vue'
import { requirePunishedContext } from './punishedContext'

const props = defineProps<{ shortId: string; owner: string }>()

const OWNER_HASH = /^[0-9a-f]{128}$/i
const { t } = useI18n()
const api = useApi()
const context = requirePunishedContext('PunishedRevokeRoute')
const dialog = useRoutedDialog({ parent: 'punished-detail', child: 'punished-revoke' })

interface Target {
  section: string
  permanent: boolean
  pardonScore: number
}
const target = ref<Target | null>(null)
const missing = ref(false)

// One punishment must have this short id and be in force; several matches never revoke a guessed one.
async function load(): Promise<void> {
  if (!OWNER_HASH.test(props.owner)) {
    missing.value = true
    return
  }
  const [found, elo] = await Promise.all([api.punishmentsByShortId(props.shortId), api.eloProfile(props.owner)])
  if (!found.ok || !elo.ok) {
    missing.value = true
    return
  }
  const active = found.data.filter((entry: Punishment) => !entry.lifted)
  const entry = found.data.length === 1 && active.length === 1 ? active[0]! : null
  const score = entry ? elo.data.sections.find((section) => section.section === entry.section) : undefined
  if (!entry || !score) {
    missing.value = true
    return
  }
  target.value = { section: entry.section, permanent: entry.permanent, pardonScore: score.pardonScore }
}

void load()

async function done(message: string): Promise<void> {
  await context.changed(message)
  await dialog.close()
}
</script>

<template>
  <RoutedDialog v-if="missing" parent="punished-detail" child="punished-revoke" :headline="t('actions.revoke.open')" not-found />
  <RevokeDialog
    v-else-if="target"
    :open="true"
    :owner="props.owner"
    :section="target.section"
    :permanent="target.permanent"
    :pardon-score="target.pardonScore"
    @close="dialog.close"
    @done="done"
  />
</template>
