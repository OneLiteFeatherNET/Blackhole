<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { useApi } from '../api/inject'
import CopyLink from '../components/CopyLink.vue'
import DefinitionList from '../components/DefinitionList.vue'
import IpBanLiftDialog from '../components/ipban/IpBanLiftDialog.vue'
import { banState } from '../components/ipban/ipBanRules'
import PageShell from '../components/PageShell.vue'
import ResourceState from '../components/ResourceState.vue'
import StatusPill from '../components/StatusPill.vue'
import MdButton from '../components/md/MdButton.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import ListItemMissing from '../components/lists/ListItemMissing.vue'
import { useResource } from '../composables/useResource'
import { formatDateTime, shortHash } from '../format'
import { useRoutedDialog } from '../router/routedDialog'
import { useSessionStore } from '../stores/session'

// One IP ban by its id. The address is shown on the page for staff; it is never part of the address of this page.
const props = defineProps<{ id: string }>()

const { t, locale } = useI18n()
const api = useApi()
const session = useSessionStore()

const ban = useResource(() => api.ipBan(props.id))
const lift = useRoutedDialog({ parent: 'ip-ban', child: 'ip-ban-lift' })
const message = ref('')

watch(
  () => props.id,
  () => void ban.reload(),
)

const missing = computed(() => ban.error.value?.kind === 'NOT_FOUND')
const state = computed(() => (ban.data.value ? banState(ban.data.value, Date.now()) : null))
const canLift = computed(() => session.moderator && state.value === 'active')

const facts = computed(() => {
  const loaded = ban.data.value
  if (!loaded || !state.value) return []
  return [
    { term: t('ipBans.columns.range'), value: loaded.range },
    { term: t('ipBans.columns.reason'), value: loaded.reason },
    { term: t('ipBans.columns.state'), value: t(`ipBans.state.${state.value}`) },
    { term: t('ipBans.columns.source'), value: t(`ipBans.source.${loaded.source}`) },
    { term: t('ipBans.columns.created'), value: formatDateTime(loaded.createdAt, locale.value) },
    { term: t('ipBans.columns.expires'), value: loaded.expiresAt ? formatDateTime(loaded.expiresAt, locale.value) : t('ipBans.permanent') },
    { term: t('ipBans.columns.actor'), value: loaded.actorOid ? shortHash(loaded.actorOid) : t('ipBans.noActor') },
    ...(loaded.liftedAt ? [{ term: t('ipBans.detail.lifted'), value: formatDateTime(loaded.liftedAt, locale.value) }] : []),
    ...(loaded.liftReason ? [{ term: t('ipBans.detail.liftReason'), value: loaded.liftReason }] : []),
  ]
})

async function done(text: string): Promise<void> {
  await lift.close()
  message.value = text
  await ban.reload()
}
</script>

<template>
  <PageShell :title="t('ipBans.detail.title')">
    <template #actions>
      <CopyLink />
    </template>

    <ListItemMissing v-if="missing" :back-to="{ name: 'ip-bans' }" :back-label="t('ipBans.detail.backToList')" />

    <template v-else>
      <p class="mb-4">
        <RouterLink :to="{ name: 'ip-bans' }" data-testid="back-to-list" class="inline-flex min-h-12 items-center text-primary underline underline-offset-2 focus-ring">
          {{ t('ipBans.detail.backToList') }}
        </RouterLink>
      </p>
    </template>

    <ResourceState v-if="!missing" :loading="ban.loading.value" :error="ban.error.value" :has-data="ban.data.value !== null" @retry="ban.reload">
      <div v-if="ban.data.value" class="flex flex-col gap-6">
        <div class="flex flex-wrap items-center gap-3">
          <StatusPill :label="t(`ipBans.state.${state}`)" :tone="state === 'active' ? 'tertiary' : 'neutral'" />
          <MdButton v-if="canLift" as="link" data-testid="lift-open" :to="{ name: 'ip-ban-lift', params: { id: props.id } }">
            {{ t('ipBans.lift.open') }}
          </MdButton>
        </div>
        <DefinitionList :items="facts" />
      </div>
    </ResourceState>

    <IpBanLiftDialog v-if="ban.data.value" :open="lift.open.value && canLift" :ban="ban.data.value" @close="lift.close" @done="done" />

    <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
  </PageShell>
</template>
