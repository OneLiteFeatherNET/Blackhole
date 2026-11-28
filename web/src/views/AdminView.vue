<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import AuditLogPanel from '../components/admin/AuditLogPanel.vue'
import CloudNetCataloguePanel from '../components/admin/CloudNetCataloguePanel.vue'
import CloudNetEntryPanel from '../components/admin/CloudNetEntryPanel.vue'
import ImportsPage from '../components/admin/ImportsPage.vue'
import KickPage from '../components/admin/KickPage.vue'
import StaffRollbackPanel from '../components/admin/StaffRollbackPanel.vue'
import PageShell from '../components/PageShell.vue'
import { useSessionStore } from '../stores/session'
import StaffAccountsView from './StaffAccountsView.vue'

// The system pages of the Administration workspace. Each page is a route of its own (`meta.adminTab`) and a rail item.
// Their entries and dialogs are child routes of the same page (see the router). ADMIN only: the router guard keeps everyone
// else away, and the page renders nothing for any other role.
const { t } = useI18n()
const route = useRoute()
const session = useSessionStore()

const PAGES = {
  staff: 'nav.staffAccounts',
  rollback: 'nav.rollback',
  imports: 'nav.imports',
  cloudnet: 'nav.cloudnet',
  kick: 'nav.kick',
  audit: 'nav.audit',
} as const

const active = computed<keyof typeof PAGES>(() => (route.meta.adminTab as keyof typeof PAGES | undefined) ?? 'staff')
</script>

<template>
  <PageShell :title="t(PAGES[active])">
    <template v-if="session.administrator">
      <div class="flex flex-col gap-8">
        <StaffAccountsView v-if="active === 'staff'" />
        <StaffRollbackPanel v-else-if="active === 'rollback'" />
        <ImportsPage v-else-if="active === 'imports'" />
        <template v-else-if="active === 'cloudnet'">
          <CloudNetEntryPanel v-if="route.name === 'admin-cloudnet-entry'" />
          <CloudNetCataloguePanel v-else />
        </template>
        <KickPage v-else-if="active === 'kick'" />
        <AuditLogPanel v-else-if="active === 'audit'" />
      </div>
    </template>
  </PageShell>
</template>
