<script setup lang="ts">
import DiscordLinksPanel from '../components/account/DiscordLinksPanel.vue'
import StaffLinksPanel from '../components/account/StaffLinksPanel.vue'
import StaffActorsPanel from '../components/admin/StaffActorsPanel.vue'
import { useSessionStore } from '../stores/session'

// The staff accounts tab of the administration area: every staff account, its Minecraft link and its Discord links.
// Admin only; the panels request their lists only when the viewer is an administrator.
const session = useSessionStore()
</script>

<template>
  <div v-if="session.administrator" class="flex flex-col gap-8">
    <StaffActorsPanel />
    <StaffLinksPanel />
    <DiscordLinksPanel />
  </div>
</template>
