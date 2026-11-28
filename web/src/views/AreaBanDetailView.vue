<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { useApi } from '../api/inject'
import CopyLink from '../components/CopyLink.vue'
import DefinitionList from '../components/DefinitionList.vue'
import AreaBanLiftDialog from '../components/areabans/AreaBanLiftDialog.vue'
import PageShell from '../components/PageShell.vue'
import ResourceState from '../components/ResourceState.vue'
import StatusPill from '../components/StatusPill.vue'
import ListItemMissing from '../components/lists/ListItemMissing.vue'
import MdButton from '../components/md/MdButton.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import { useResource } from '../composables/useResource'
import { formatDateTime } from '../format'
import { useRoutedDialog } from '../router/routedDialog'
import { useSessionStore } from '../stores/session'

// One area ban by its id, lifted and expired ones included.
const props = defineProps<{ id: string }>()

const { t, locale } = useI18n()
const api = useApi()
const session = useSessionStore()

const ban = useResource(() => api.areaBan(props.id))
const lift = useRoutedDialog({ parent: 'area-ban', child: 'area-ban-lift' })
const message = ref('')

watch(
  () => props.id,
  () => void ban.reload(),
)

const missing = computed(() => ban.error.value?.kind === 'NOT_FOUND')
const active = computed(() => ban.data.value?.active === true && ban.data.value.liftedAt == null)
const canLift = computed(() => session.moderator && active.value)

const facts = computed(() => {
  const loaded = ban.data.value
  if (!loaded) return []
  return [
    { term: t('areaBans.columns.value'), value: `${t(`areaBans.kind.${loaded.kind}`)}: ${loaded.value}` },
    { term: t('areaBans.columns.reason'), value: loaded.reason },
    { term: t('areaBans.columns.source'), value: loaded.source },
    { term: t('areaBans.columns.status'), value: t(`areaBans.status.${active.value ? 'active' : loaded.liftedAt ? 'lifted' : 'expired'}`) },
    { term: t('areaBans.detail.created'), value: formatDateTime(loaded.createdAt, locale.value) },
    {
      term: t('areaBans.columns.expires'),
      value: loaded.expiresAt ? formatDateTime(loaded.expiresAt, locale.value) : loaded.active ? t('areaBans.permanent') : '',
    },
    ...(loaded.liftedAt ? [{ term: t('areaBans.detail.lifted'), value: formatDateTime(loaded.liftedAt, locale.value) }] : []),
  ]
})

async function done(): Promise<void> {
  await lift.close()
  message.value = t('areaBans.lifted')
  await ban.reload()
}
</script>

<template>
  <PageShell :title="t('areaBans.detail.title')">
    <template #actions>
      <CopyLink />
    </template>

    <ListItemMissing v-if="missing" :back-to="{ name: 'area-bans' }" :back-label="t('areaBans.detail.backToList')" />

    <template v-else>
      <p class="mb-4">
        <RouterLink :to="{ name: 'area-bans' }" data-testid="back-to-list" class="inline-flex min-h-12 items-center text-primary underline underline-offset-2 focus-ring">
          {{ t('areaBans.detail.backToList') }}
        </RouterLink>
      </p>
      <ResourceState :loading="ban.loading.value" :error="ban.error.value" :has-data="ban.data.value !== null" @retry="ban.reload">
        <div v-if="ban.data.value" class="flex flex-col gap-6">
          <div class="flex flex-wrap items-center gap-3">
            <StatusPill :label="t(`areaBans.status.${active ? 'active' : 'lifted'}`)" :tone="active ? 'error' : 'neutral'" />
            <MdButton v-if="canLift" as="link" data-testid="lift-open" :to="{ name: 'area-ban-lift', params: { id: props.id } }">
              {{ t('areaBans.lift.open') }}
            </MdButton>
          </div>
          <DefinitionList :items="facts" />
        </div>
      </ResourceState>
    </template>

    <AreaBanLiftDialog :ban="lift.open.value && canLift ? ban.data.value : null" @close="lift.close" @lifted="done" />

    <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
  </PageShell>
</template>
