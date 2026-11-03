<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Breadcrumbs from '../components/Breadcrumbs.vue'
import { withWorkspace } from '../nav/workspaces'
import { useSessionStore } from '../stores/session'
import PageShell from '../components/PageShell.vue'
import TextLink from '../components/TextLink.vue'
import PlayerDetail from '../components/player/PlayerDetail.vue'
import { shortHash } from '../format'
import { useLookupStore } from '../stores/lookup'

const props = defineProps<{ owner: string }>()

const OWNER_HASH = /^[0-9a-f]{128}$/i

const { t } = useI18n()
const lookup = useLookupStore()
const validOwner = computed(() => OWNER_HASH.test(props.owner))

// The title is the player's last known name (the name lists of the page, or the lookup of this session), and the
// short hash is kept under it so two accounts with one name stay apart. Without a name the title is the short hash.
// The way back to the lookup is the breadcrumb trail.
const knownName = computed(() => (validOwner.value ? lookup.nameOf(props.owner) : null))
const title = computed(() => (validOwner.value ? (knownName.value ?? shortHash(props.owner)) : t('nav.playerLookup')))
const description = computed(() => (knownName.value ? shortHash(props.owner) : undefined))
const crumbs = computed(() =>
  validOwner.value ? [{ label: t('nav.playerLookup'), to: '/players' }, { label: title.value }] : [{ label: t('nav.playerLookup') }],
)
// The workspace leads the trail for an administrator (the switcher is shown to them only).
const session = useSessionStore()
const trail = (items: { label: string; to?: string }[]) => withWorkspace(items, { show: session.administrator, workspace: 'moderation', translate: (key) => t(key) })
</script>

<template>
  <!-- The page heading is the player's header (PlayerDetail renders it): the detail only mounts, and so only calls the API, for a well-formed owner hash. -->
  <div v-if="validOwner" class="mx-auto flex w-full max-w-6xl flex-col gap-6">
    <PlayerDetail :key="owner" :owner="owner" :title="title" :description="description" :crumbs="trail(crumbs)" />
  </div>
  <PageShell v-else :title="title">
    <template #breadcrumbs>
      <Breadcrumbs :items="trail(crumbs)" />
    </template>
    <div role="alert" class="flex flex-col items-start gap-3 rounded-md-md bg-error-container p-4 text-on-error-container">
      <p>{{ t('player.invalidOwner') }}</p>
      <TextLink to="/players">{{ t('player.backToSearch') }}</TextLink>
    </div>
  </PageShell>
</template>
