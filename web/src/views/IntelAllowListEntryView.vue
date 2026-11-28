<script setup lang="ts">
// One VPN and hosting exemption by the owner hash (128 hex characters). A moderator removes it in the child route `remove`.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRouter } from 'vue-router'
import { useApi } from '../api/inject'
import CopyLink from '../components/CopyLink.vue'
import DefinitionList from '../components/DefinitionList.vue'
import PageShell from '../components/PageShell.vue'
import ListItemMissing from '../components/lists/ListItemMissing.vue'
import MdButton from '../components/md/MdButton.vue'
import MdDialog from '../components/md/MdDialog.vue'
import ResourceState from '../components/ResourceState.vue'
import StatusPill from '../components/StatusPill.vue'
import { useResource } from '../composables/useResource'
import { formatDateTime, shortHash } from '../format'
import { useRoutedDialog } from '../router/routedDialog'
import { useSessionStore } from '../stores/session'

const OWNER_HASH = /^[a-fA-F0-9]{128}$/

const props = defineProps<{ owner: string }>()

const { t, locale } = useI18n()
const api = useApi()
const session = useSessionStore()
const router = useRouter()

// A value that is not an owner hash is not looked up at all: the page says the entry does not exist.
const wellFormed = computed(() => OWNER_HASH.test(props.owner))
const entry = useResource(() => api.intelAllowListEntry(props.owner), { immediate: wellFormed.value })
watch(
  () => props.owner,
  () => {
    if (wellFormed.value) void entry.reload()
  },
)
const remove = useRoutedDialog({ parent: 'intel-allow-entry', child: 'intel-allow-entry-remove' })
const removing = ref(false)
const failed = ref('')

const missing = computed(() => !wellFormed.value || entry.error.value?.kind === 'NOT_FOUND')
const facts = computed(() => {
  const loaded = entry.data.value
  if (!loaded) return []
  const until =
    loaded.expiresAt === null || loaded.expiresAt === undefined
      ? t('intel.allowList.permanentLabel')
      : t('intel.allowList.until', { when: formatDateTime(loaded.expiresAt, locale.value) })
  return [
    { term: t('intel.allowList.columns.reason'), value: loaded.reason },
    { term: t('intel.allowList.columns.expires'), value: until },
    { term: t('intel.allowList.columns.added'), value: formatDateTime(loaded.createdAt, locale.value) },
    { term: t('intel.allowList.columns.actor'), value: loaded.actorOid ? shortHash(loaded.actorOid) : t('ipBans.noActor') },
  ]
})

/** The exemption is removed with the version-less endpoint: the confirmation of the dialog is the only guard. */
async function confirmRemove(): Promise<void> {
  if (removing.value) return
  removing.value = true
  failed.value = ''
  const result = await api.removeIntelAllowListEntry(props.owner)
  removing.value = false
  if (!result.ok) {
    failed.value = result.error.message
    return
  }
  await router.replace({ name: 'intel-allow-list' })
}
</script>

<template>
  <PageShell :title="t('intel.allowList.entry.title')" :description="t('intel.allowList.intro')">
    <template #actions>
      <CopyLink />
    </template>

    <ListItemMissing v-if="missing" :back-to="{ name: 'intel-allow-list' }" :back-label="t('intel.allowList.entry.back')" />

    <template v-else>
      <p class="mb-4">
        <RouterLink :to="{ name: 'intel-allow-list' }" data-testid="back-to-list" class="inline-flex min-h-12 items-center text-primary underline underline-offset-2 focus-ring">
          {{ t('intel.allowList.entry.back') }}
        </RouterLink>
      </p>
      <ResourceState :loading="entry.loading.value" :error="entry.error.value" :has-data="entry.data.value !== null" @retry="entry.reload">
        <div v-if="entry.data.value" class="flex flex-col gap-6">
          <div class="flex flex-wrap items-center gap-3">
            <StatusPill
              :label="entry.data.value.expired ? t('intel.allowList.expired') : t('intel.allowList.entry.active')"
              :tone="entry.data.value.expired ? 'error' : 'neutral'"
            />
            <MdButton v-if="session.moderator" as="link" data-testid="remove-open" :to="{ name: 'intel-allow-entry-remove', params: { owner: props.owner } }">
              {{ t('intel.allowList.remove.open') }}
            </MdButton>
          </div>
          <p class="break-all font-mono text-body-medium" data-testid="owner">{{ props.owner }}</p>
          <DefinitionList :items="facts" />
        </div>
      </ResourceState>
    </template>

    <MdDialog v-if="!missing && session.moderator" :open="remove.open.value" :headline="t('intel.allowList.remove.title')" @close="remove.close">
      <p data-testid="remove-body">{{ t('intel.allowList.remove.body') }}</p>
      <p v-if="failed" role="alert" data-testid="remove-error" class="mt-4 rounded-md-md bg-error-container p-3 text-on-error-container">{{ failed }}</p>
      <template #actions>
        <MdButton variant="text" data-testid="remove-cancel" @click="remove.close">{{ t('actions.cancel') }}</MdButton>
        <MdButton data-testid="remove-confirm" :disabled="removing" @click="confirmRemove">{{ t('intel.allowList.remove.confirm') }}</MdButton>
      </template>
    </MdDialog>
  </PageShell>
</template>
