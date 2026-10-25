<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useApi } from '../api/inject'
import PageShell from '../components/PageShell.vue'
import MdButton from '../components/md/MdButton.vue'
import MdCard from '../components/md/MdCard.vue'
import MdProgress from '../components/md/MdProgress.vue'
import MdTextField from '../components/md/MdTextField.vue'
import { useLookupStore } from '../stores/lookup'

// Same rule the backend applies before it asks a resolver.
const MINECRAFT_NAME = /^[a-zA-Z0-9_]{3,16}$/

const { t } = useI18n()
const api = useApi()
const router = useRouter()
const lookup = useLookupStore()

const name = ref('')
const fieldError = ref('')
const failure = ref('')
const searching = ref(false)

async function submit(): Promise<void> {
  const wanted = name.value.trim()
  failure.value = ''
  fieldError.value = MINECRAFT_NAME.test(wanted) ? '' : t('players.invalidName')
  if (fieldError.value) return

  searching.value = true
  const result = await api.resolvePlayer(wanted)
  searching.value = false
  if (!result.ok) {
    failure.value = result.error.kind === 'NOT_FOUND' ? t('players.notFound', { name: wanted }) : result.error.message
    return
  }
  lookup.remember(result.data.owner, result.data.name)
  await router.push(`/players/${result.data.owner}`)
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
  </PageShell>
</template>
