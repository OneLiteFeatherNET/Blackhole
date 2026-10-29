<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { ApiError, EloEvent, EloProfile, EloSectionScore, Punishment, PunishmentProfile } from '../../api/types'
import { useResource } from '../../composables/useResource'
import { useLookupStore } from '../../stores/lookup'
import { useSessionStore } from '../../stores/session'
import KickDialog from '../kick/KickDialog.vue'
import MdButton from '../md/MdButton.vue'
import MdSnackbar from '../md/MdSnackbar.vue'
import ResourceState from '../ResourceState.vue'
import ActivePunishments from './ActivePunishments.vue'
import AdjustDialog from './AdjustDialog.vue'
import ApplyTemplateDialog from './ApplyTemplateDialog.vue'
import ClientRuleHits from '../clientrules/ClientRuleHits.vue'
import EloHistory from './EloHistory.vue'
import EvasionHints from './EvasionHints.vue'
import PlayerSessions from './PlayerSessions.vue'
import PunishmentHistory from './PunishmentHistory.vue'
import RevokeDialog from './RevokeDialog.vue'
import SectionScores from './SectionScores.vue'
import WarnDialog from '../warning/WarnDialog.vue'

const props = defineProps<{ owner: string }>()

const { t } = useI18n()
const api = useApi()
const session = useSessionStore()
const lookup = useLookupStore()


// Profile and scores are loaded together so every action can refresh both with one reload.
const player = useResource(async () => {
  const [profile, elo] = await Promise.all([api.profile(props.owner), api.eloProfile(props.owner)])
  if (!profile.ok) return profile
  if (!elo.ok) return elo
  return { ok: true as const, data: { profile: profile.data, elo: elo.data } as { profile: PunishmentProfile; elo: EloProfile } }
})

const section = ref('')
const events = ref<EloEvent[] | null>(null)
const eventsLoading = ref(false)
const eventsError = ref<ApiError | null>(null)

// The UUID is not in the profile; the newest session of the player is the one place it is shown, so the
// apply sends it when that session exists. Without a session the apply goes out without it.
const playerUuid = ref<string | null>(null)
watch(
  () => props.owner,
  async (owner) => {
    const result = await api.playerSessions(owner, 0, 1)
    if (owner !== props.owner) return // the player changed while the answer was on its way
    playerUuid.value = result.ok ? (result.data.sessions[0]?.playerUuid ?? null) : null
  },
  { immediate: true },
)

const sectionOptions = computed(() =>
  (player.data.value?.elo.sections ?? []).map((entry) => ({ value: entry.section, label: entry.displayName })),
)

async function loadEvents(): Promise<void> {
  if (!section.value) return
  const requested = section.value
  eventsLoading.value = true
  const result = await api.eloHistory(props.owner, requested)
  if (requested !== section.value) return // a newer selection superseded this answer
  eventsLoading.value = false
  if (result.ok) {
    events.value = result.data
    eventsError.value = null
  } else {
    eventsError.value = result.error
  }
}

watch(
  () => player.data.value,
  (data) => {
    if (data && !section.value) section.value = data.elo.sections[0]?.section ?? ''
  },
  { immediate: true },
)
watch(section, loadEvents, { immediate: true })

// ---- staff actions ----------------------------------------------------------------------------

const applyOpen = ref(false)
const warnOpen = ref(false)
const kickOpen = ref(false)
const revokeTarget = ref<{ section: string; punishment: Punishment } | null>(null)
const adjustTarget = ref<EloSectionScore | null>(null)
const message = ref('')

const pardonScoreOf = (key: string) => player.data.value?.elo.sections.find((entry) => entry.section === key)?.pardonScore ?? 0
const hasActivePermanent = (key: string) => player.data.value?.profile.active[key]?.permanent === true

/** Closes the dialog that finished, shows what happened and refreshes everything the action may have changed. */
async function finished(done: string): Promise<void> {
  applyOpen.value = false
  revokeTarget.value = null
  adjustTarget.value = null
  message.value = done
  await Promise.all([player.reload(), loadEvents()])
}

// A warning keeps its dialog open (it shows the step applied); only the history behind it is refreshed.
async function warned(done: string): Promise<void> {
  message.value = done
  await Promise.all([player.reload(), loadEvents()])
}
</script>

<template>
  <ResourceState
    :loading="player.loading.value"
    :error="player.error.value"
    :has-data="player.data.value !== null"
    @retry="player.reload"
  >
    <div v-if="player.data.value" class="flex flex-col gap-8">
      <header v-if="session.moderator" class="flex flex-wrap justify-end gap-2">
        <MdButton variant="outlined" data-testid="open-warn" @click="warnOpen = true">{{ t('warn.open') }}</MdButton>
        <MdButton variant="outlined" data-testid="open-kick" @click="kickOpen = true">{{ t('kick.open') }}</MdButton>
        <MdButton variant="tonal" data-testid="open-apply" @click="applyOpen = true">
          {{ t('actions.apply.open') }}
        </MdButton>
      </header>

      <section aria-labelledby="scores-heading">
        <h2 id="scores-heading" class="mb-3 text-title-medium">{{ t('player.scores') }}</h2>
        <SectionScores :scores="player.data.value.elo.sections">
          <template #actions="{ entry }">
            <div v-if="session.moderator" class="mt-3 flex justify-end">
              <MdButton variant="text" :data-testid="`open-adjust-${entry.section}`" @click="adjustTarget = entry">
                {{ t('actions.adjust.open') }}
              </MdButton>
            </div>
          </template>
        </SectionScores>
      </section>

      <section aria-labelledby="active-heading">
        <h2 id="active-heading" class="mb-3 text-title-medium">{{ t('player.active') }}</h2>
        <ActivePunishments :active="player.data.value.profile.active">
          <template #actions="{ section: key, punishment }">
            <div v-if="session.moderator" class="mt-3 flex justify-end">
              <MdButton variant="outlined" :data-testid="`open-revoke-${key}`" @click="revokeTarget = { section: key, punishment }">
                {{ t('actions.revoke.open') }}
              </MdButton>
            </div>
          </template>
        </ActivePunishments>
      </section>

      <section aria-labelledby="history-heading">
        <h2 id="history-heading" class="mb-3 text-title-medium">{{ t('player.history') }}</h2>
        <PunishmentHistory :history="player.data.value.profile.history" />
      </section>

      <section aria-labelledby="elo-heading">
        <h2 id="elo-heading" class="mb-3 text-title-medium">{{ t('player.eloHistory') }}</h2>
        <EloHistory
          v-model:section="section"
          :sections="sectionOptions"
          :events="events"
          :loading="eventsLoading"
          :error="eventsError"
        />
      </section>

      <section aria-labelledby="evasion-heading">
        <h2 id="evasion-heading" class="mb-3 text-title-medium">{{ t('player.evasion.title') }}</h2>
        <EvasionHints :owner="owner" />
      </section>

      <PlayerSessions :owner="owner" />

      <section aria-labelledby="client-rules-heading">
        <h2 id="client-rules-heading" class="mb-3 text-title-medium">{{ t('clientRules.player.title') }}</h2>
        <ClientRuleHits :owner="owner" />
      </section>

      <ApplyTemplateDialog :open="applyOpen" :owner="owner" :player-uuid="playerUuid" @close="applyOpen = false" @done="finished" />
      <WarnDialog
        v-if="session.moderator"
        :open="warnOpen"
        :owner="owner"
        @close="warnOpen = false"
        @done="warned"
      />
      <KickDialog
        v-if="session.moderator"
        :open="kickOpen"
        :owner="owner"
        :name="lookup.nameOf(owner) ?? ''"
        @close="kickOpen = false"
        @done="player.reload"
      />
      <RevokeDialog
        :open="revokeTarget !== null"
        :owner="owner"
        :section="revokeTarget?.section ?? ''"
        :permanent="revokeTarget?.punishment.permanent ?? false"
        :pardon-score="pardonScoreOf(revokeTarget?.section ?? '')"
        @close="revokeTarget = null"
        @done="finished"
      />
      <AdjustDialog
        v-if="adjustTarget"
        :open="true"
        :owner="owner"
        :entry="adjustTarget"
        :has-active-permanent="hasActivePermanent(adjustTarget.section)"
        @close="adjustTarget = null"
        @done="finished"
      />
    </div>
  </ResourceState>
  <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
</template>
