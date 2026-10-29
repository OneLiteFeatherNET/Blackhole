<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useApi } from '../api/inject'
import type { NameHistoryOwner } from '../api/types'
import PageShell from '../components/PageShell.vue'
import MdButton from '../components/md/MdButton.vue'
import MdCard from '../components/md/MdCard.vue'
import MdProgress from '../components/md/MdProgress.vue'
import MdTextField from '../components/md/MdTextField.vue'
import { formatDateTime } from '../format'
import { useLookupStore } from '../stores/lookup'

// Same rule the backend applies before it asks a resolver.
const MINECRAFT_NAME = /^[a-zA-Z0-9_]{3,16}$/

const { t, locale } = useI18n()
const api = useApi()
const router = useRouter()
const lookup = useLookupStore()

const name = ref('')
const fieldError = ref('')
const failure = ref('')
const searching = ref(false)
// Set when the old name matches several accounts; the user picks which one to open.
const choices = ref<NameHistoryOwner[]>([])

async function openPlayer(owner: string, currentName: string): Promise<void> {
  lookup.remember(owner, currentName)
  await router.push(`/players/${owner}`)
}

async function submit(): Promise<void> {
  const wanted = name.value.trim()
  failure.value = ''
  choices.value = []
  fieldError.value = MINECRAFT_NAME.test(wanted) ? '' : t('players.invalidName')
  if (fieldError.value) return

  searching.value = true
  const live = await api.resolvePlayer(wanted)
  if (live.ok) {
    searching.value = false
    await openPlayer(live.data.owner, live.data.name)
    return
  }
  if (live.error.kind !== 'NOT_FOUND') {
    searching.value = false
    failure.value = live.error.message
    return
  }

  // The live lookup knows no such player, so the name may be an old one of an account we have seen.
  const old = await api.findNameOwners(wanted)
  searching.value = false
  if (!old.ok) {
    failure.value = old.error.kind === 'NOT_FOUND' ? t('players.notFound', { name: wanted }) : old.error.message
    return
  }
  if (old.data.length === 0) {
    failure.value = t('players.notFound', { name: wanted })
  } else if (old.data.length === 1) {
    await openPlayer(old.data[0]!.owner, old.data[0]!.currentName)
  } else {
    choices.value = old.data
  }
}
</script>

<template>
  <PageShell :title="t('nav.playerLookup')" :description="t('players.intro')">
    <MdCard variant="outlined" class="max-w-xl">
      <form class="flex flex-col gap-4" novalidate @submit.prevent="submit">
        <MdTextField v-model="name" :label="t('players.name')" required :error="fieldError" :supporting-text="t('players.nameHint')" />
        <MdProgress v-if="searching" :label="t('state.loading')" />
        <p v-if="failure" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">{{ failure }}</p>
        <div>
          <MdButton type="submit" :disabled="searching">{{ t('players.search') }}</MdButton>
        </div>
      </form>
    </MdCard>

    <MdCard v-if="choices.length > 0" variant="outlined" class="mt-4 max-w-xl" data-testid="owner-choices">
      <p class="mb-3 text-body-large">{{ t('players.choose') }}</p>
      <ul class="flex flex-col gap-2">
        <li v-for="entry in choices" :key="entry.owner">
          <MdButton variant="outlined" class="w-full" data-testid="owner-choice" @click="openPlayer(entry.owner, entry.currentName)">
            {{ entry.currentName }} · {{ t('players.lastSeen', { date: formatDateTime(entry.lastSeen, locale) }) }}
          </MdButton>
        </li>
      </ul>
    </MdCard>
  </PageShell>
</template>
