<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import AuditLogPanel from '../components/admin/AuditLogPanel.vue'
import CloudNetCataloguePanel from '../components/admin/CloudNetCataloguePanel.vue'
import ImportedPunishmentsPanel from '../components/admin/ImportedPunishmentsPanel.vue'
import VanillaImportPanel from '../components/admin/VanillaImportPanel.vue'
import MassKickForm from '../components/admin/MassKickForm.vue'
import StaffRollbackPanel from '../components/admin/StaffRollbackPanel.vue'
import PageShell from '../components/PageShell.vue'
import { useSessionStore } from '../stores/session'
import StaffAccountsView from './StaffAccountsView.vue'

// The system pages of the Administration workspace. Each page is a route of its own (`meta.adminTab`) and a rail item.
// ADMIN only: the router guard keeps everyone else away, and the page renders nothing for any other role.
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
        <template v-else-if="active === 'imports'">
          <VanillaImportPanel />
          <ImportedPunishmentsPanel />
        </template>
        <CloudNetCataloguePanel v-else-if="active === 'cloudnet'" />
        <MassKickForm v-else-if="active === 'kick'" />
        <AuditLogPanel v-else-if="active === 'audit'" />
      </div>
    </template>
  </PageShell>
</template>
