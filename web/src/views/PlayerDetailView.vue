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

// The title is the player (the name when this session looked it up, the short hash otherwise); the way back to the
// lookup is the breadcrumb trail.
const title = computed(() => (validOwner.value ? (lookup.nameOf(props.owner) ?? shortHash(props.owner)) : t('nav.playerLookup')))
const crumbs = computed(() =>
  validOwner.value ? [{ label: t('nav.playerLookup'), to: '/players' }, { label: title.value }] : [{ label: t('nav.playerLookup') }],
)
// The workspace leads the trail for an administrator (the switcher is shown to them only).
const session = useSessionStore()
const trail = (items: { label: string; to?: string }[]) => withWorkspace(items, { show: session.administrator, workspace: 'moderation', translate: (key) => t(key) })
</script>

<template>
  <PageShell :title="title">
    <template #breadcrumbs>
      <Breadcrumbs :items="trail(crumbs)" />
    </template>

    <!-- The detail only mounts (and so only calls the API) for a well-formed owner hash. -->
    <PlayerDetail v-if="validOwner" :key="owner" :owner="owner" />
    <div v-else role="alert" class="flex flex-col items-start gap-3 rounded-md-md bg-error-container p-4 text-on-error-container">
      <p>{{ t('player.invalidOwner') }}</p>
      <TextLink to="/players">{{ t('player.backToSearch') }}</TextLink>
    </div>
  </PageShell>
</template>
