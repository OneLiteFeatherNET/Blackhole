<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useApi } from '../api/inject'
import type { GuardLevel } from '../api/types'
import DefinitionList from '../components/DefinitionList.vue'
import PageShell from '../components/PageShell.vue'
import { GUARD_LEVELS, guardSource } from '../components/guard/guardState'
import MdButton from '../components/md/MdButton.vue'
import MdDialog from '../components/md/MdDialog.vue'
import MdSelect from '../components/md/MdSelect.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import ResourceState from '../components/ResourceState.vue'
import { useResource } from '../composables/useResource'
import { formatDateTime } from '../format'
import { useRoutedDialog } from '../router/routedDialog'
import { useSessionStore } from '../stores/session'

// The join guard: the level in force and where it comes from, and the manual level moderators set. Everyone with
// access reads the state; only moderators and admins change the manual level, in the child route `level`, after a
// confirmation. The level to set is the query of that route, so a reload shows the same question.
const { t, locale } = useI18n()
const api = useApi()
const session = useSessionStore()
const route = useRoute()
const router = useRouter()
const state = useResource(() => api.joinGuard())
const levelRoute = useRoutedDialog({ parent: 'guard', child: 'guard-level' })

const chosen = ref<GuardLevel | ''>('')
const saving = ref(false)
const error = ref('')
const message = ref('')

/** The level the confirmation asks about, from the route; anything else is no level at all. */
const pendingLevel = computed<GuardLevel | null>(() => {
  const raw = route.query.level
  return GUARD_LEVELS.find((level) => level === raw) ?? null
})

watch(
  () => state.data.value,
  (loaded) => {
    if (loaded) chosen.value = loaded.manualLevel as GuardLevel
  },
  { immediate: true },
)

const levelOptions = computed(() => GUARD_LEVELS.map((level) => ({ value: level, label: t(`guard.level.${level}`) })))
const source = computed(() => (state.data.value ? guardSource(state.data.value) : null))
const changed = computed(() => chosen.value !== '' && chosen.value !== state.data.value?.manualLevel)

const facts = computed(() => {
  const loaded = state.data.value
  if (!loaded) return []
  return [
    { term: t('guard.manualLevel'), value: t(`guard.level.${loaded.manualLevel}`) },
    { term: t('guard.unattendedLevel'), value: t(`guard.level.${loaded.automaticLevel}`) },
    { term: t('guard.updatedAt'), value: formatDateTime(loaded.updatedAt, locale.value) },
  ]
})

function askChange(): void {
  if (!chosen.value) return
  void router.push({ name: 'guard-level', query: { level: chosen.value } })
}

async function confirmChange(): Promise<void> {
  const level = pendingLevel.value
  if (!level || saving.value) return
  saving.value = true
  error.value = ''
  const result = await api.setJoinGuardLevel(level)
  saving.value = false
  if (!result.ok) {
    error.value = result.error.message
    return
  }
  // The level is in the query of the child route, so the page is left by replacing that entry, not by Back.
  await router.replace({ name: 'guard' })
  message.value = t('guard.saved', { level: t(`guard.level.${level}`) })
  await state.reload()
}
</script>

<template>
  <PageShell :title="t('guard.title')" :description="t('guard.intro')">
    <ResourceState :loading="state.loading.value" :error="state.error.value" :has-data="state.data.value !== null" @retry="state.reload">
      <div v-if="state.data.value && source" class="flex flex-col gap-6">
        <section aria-labelledby="effective-heading" class="flex flex-col gap-2" data-testid="effective">
          <h2 id="effective-heading" class="text-title-medium">{{ t('guard.effective') }}</h2>
          <p class="text-headline-small" data-testid="effective-level">{{ t(`guard.level.${state.data.value.effectiveLevel}`) }}</p>
          <p class="text-body-large text-on-surface-variant" data-testid="effective-source">{{ t(`guard.source.${source}`) }}</p>
        </section>

        <DefinitionList :items="facts" />

        <section v-if="session.moderator" aria-labelledby="set-heading" class="flex flex-col gap-3">
          <h2 id="set-heading" class="text-title-medium">{{ t('guard.set.title') }}</h2>
          <p class="text-body-medium text-on-surface-variant">{{ t('guard.set.hint') }}</p>
          <div class="flex flex-wrap items-start gap-3">
            <MdSelect
              v-model="chosen"
              data-testid="guard-level"
              class="min-w-56"
              :label="t('guard.manualLevel')"
              :options="levelOptions"
            />
            <MdButton data-testid="set-level" :disabled="!changed" @click="askChange">{{ t('guard.set.open') }}</MdButton>
          </div>
        </section>
        <p v-else data-testid="read-only" class="text-body-medium text-on-surface-variant">{{ t('guard.readOnly') }}</p>
      </div>
    </ResourceState>

    <section aria-labelledby="settings-link-heading" class="mt-8 flex flex-col gap-2">
      <h2 id="settings-link-heading" class="text-title-medium">{{ t('guard.settings.title') }}</h2>
      <p class="text-body-medium text-on-surface-variant">{{ t('guard.settings.intro') }}</p>
      <RouterLink
        :to="{ name: 'guard-settings' }"
        data-testid="settings-link"
        class="inline-flex min-h-12 items-center text-primary underline underline-offset-2 focus-ring"
      >
        {{ t('guard.settings.open') }}
      </RouterLink>
    </section>

    <MdDialog :open="levelRoute.open.value" :headline="t('guard.confirm.title')" @close="router.replace({ name: 'guard' })">
      <p v-if="pendingLevel" data-testid="confirm-body">{{ t('guard.confirm.body', { level: t(`guard.level.${pendingLevel}`) }) }}</p>
      <p v-else data-testid="confirm-missing" role="alert">{{ t('routedDialog.notFound') }}</p>
      <p v-if="error" data-testid="error" role="alert" class="mt-4 rounded-md-md bg-error-container p-3 text-on-error-container">
        {{ error }}
      </p>
      <template #actions>
        <MdButton variant="text" data-testid="cancel" @click="router.replace({ name: 'guard' })">{{ t('actions.cancel') }}</MdButton>
        <MdButton v-if="pendingLevel" data-testid="confirm" :disabled="saving" @click="confirmChange">{{ t('guard.confirm.confirm') }}</MdButton>
      </template>
    </MdDialog>

    <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
  </PageShell>
</template>
