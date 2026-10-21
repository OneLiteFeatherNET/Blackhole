<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { useApi } from '../../api/inject'
import type { ApiError, EloEvent, EloProfile, PunishmentProfile } from '../../api/types'
import ActivePunishments from './ActivePunishments.vue'
import EloHistory from './EloHistory.vue'
import PunishmentHistory from './PunishmentHistory.vue'
import SectionScores from './SectionScores.vue'
import ResourceState from '../ResourceState.vue'
import { useResource } from '../../composables/useResource'
import { shortHash } from '../../format'
import { useLookupStore } from '../../stores/lookup'

const props = defineProps<{ owner: string }>()

const { t } = useI18n()
const api = useApi()
const lookup = useLookupStore()

const title = computed(() => lookup.nameOf(props.owner) ?? shortHash(props.owner))

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

defineExpose({ reload: async () => Promise.all([player.reload(), loadEvents()]) })
</script>

<template>
  <ResourceState
    :loading="player.loading.value"
    :error="player.error.value"
    :has-data="player.data.value !== null"
    @retry="player.reload"
  >
    <div v-if="player.data.value" class="flex flex-col gap-8">
      <header class="flex flex-wrap items-baseline gap-x-4 gap-y-1">
        <h2 data-testid="player-title" :title="owner" class="text-headline-medium">{{ title }}</h2>
        <RouterLink to="/players" class="text-label-large text-primary underline underline-offset-2">
          {{ t('player.backToSearch') }}
        </RouterLink>
      </header>

      <section aria-labelledby="scores-heading">
        <h2 id="scores-heading" class="mb-3 text-title-large">{{ t('player.scores') }}</h2>
        <SectionScores :scores="player.data.value.elo.sections" />
      </section>

      <section aria-labelledby="active-heading">
        <h2 id="active-heading" class="mb-3 text-title-large">{{ t('player.active') }}</h2>
        <ActivePunishments :active="player.data.value.profile.active" />
      </section>

      <section aria-labelledby="history-heading">
        <h2 id="history-heading" class="mb-3 text-title-large">{{ t('player.history') }}</h2>
        <PunishmentHistory :history="player.data.value.profile.history" />
      </section>

      <section aria-labelledby="elo-heading">
        <h2 id="elo-heading" class="mb-3 text-title-large">{{ t('player.eloHistory') }}</h2>
        <EloHistory
          v-model:section="section"
          :sections="sectionOptions"
          :events="events"
          :loading="eventsLoading"
          :error="eventsError"
        />
      </section>
    </div>
  </ResourceState>
</template>
