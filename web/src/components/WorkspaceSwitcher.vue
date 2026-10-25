<script setup lang="ts">
// The workspace switcher at the top left of the app bar. Shown to ADMIN only: every other role has the
// Moderation workspace alone and sees no switcher. Each entry is a link to the workspace home. It is a group,
// not a navigation landmark, so the navigation rail stays the only `nav` of the page.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute } from 'vue-router'
import type { WorkspaceKey } from '../nav/navigation'
import { storeWorkspace, visibleWorkspaces } from '../nav/workspaces'
import { useSessionStore } from '../stores/session'

const { t } = useI18n()
const route = useRoute()
const session = useSessionStore()

const entries = computed(() => visibleWorkspaces({ hasAccess: session.hasAccess, administrator: session.administrator }))
/** The workspace of the current route; a route without one belongs to Moderation. */
const current = computed<WorkspaceKey>(() => (route.meta.workspace as WorkspaceKey | undefined) ?? 'moderation')
</script>

<template>
  <div v-if="entries.length > 1" data-testid="workspace-switcher" role="group" :aria-label="t('workspace.switch')" class="flex items-center gap-1">
    <RouterLink
      v-for="entry in entries"
      :key="entry.key"
      :to="entry.home"
      :data-testid="`workspace-${entry.key}`"
      :aria-current="current === entry.key ? 'true' : undefined"
      class="md-state rounded-md-full px-3 py-2 text-label-large"
      :class="current === entry.key ? 'bg-secondary-container text-on-secondary-container' : 'text-on-surface-variant'"
      @click="storeWorkspace(entry.key)"
    >
      {{ t(entry.label) }}
    </RouterLink>
  </div>
</template>
