<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute } from 'vue-router'
import CloudNetCataloguePanel from '../components/admin/CloudNetCataloguePanel.vue'
import ImportedPunishmentsPanel from '../components/admin/ImportedPunishmentsPanel.vue'
import VanillaImportPanel from '../components/admin/VanillaImportPanel.vue'
import PageShell from '../components/PageShell.vue'
import { useSessionStore } from '../stores/session'
import StaffAccountsView from './StaffAccountsView.vue'

// The administration area: one heading and three tabs, each tab a route of its own (see the router, `meta.adminTab`).
// ADMIN only: the router guard keeps everyone else away, and the area renders nothing for any other role.
const { t } = useI18n()
const route = useRoute()
const session = useSessionStore()

const TABS = [
  { key: 'staff', route: 'admin-staff', label: 'admin.tabs.staff' },
  { key: 'imports', route: 'admin-imports', label: 'admin.tabs.imports' },
  { key: 'cloudnet', route: 'admin-cloudnet', label: 'admin.tabs.cloudnet' },
] as const

const active = computed<string>(() => (route.meta.adminTab as string | undefined) ?? 'staff')
const tabId = (key: string): string => `admin-tab-${key}`
</script>

<template>
  <PageShell :title="t('admin.title')" :description="t('admin.intro')">
    <template v-if="session.administrator">
      <div :aria-label="t('admin.title')" class="flex flex-wrap gap-2 border-b border-outline-variant" role="tablist">
        <RouterLink
          v-for="tab in TABS"
          :id="tabId(tab.key)"
          :key="tab.key"
          :to="{ name: tab.route }"
          role="tab"
          :aria-selected="active === tab.key ? 'true' : 'false'"
          :data-testid="`tab-${tab.key}`"
          class="md-state -mb-px border-b-2 px-4 py-3 text-label-large"
          :class="active === tab.key ? 'border-primary text-primary' : 'border-transparent text-on-surface-variant'"
        >
          {{ t(tab.label) }}
        </RouterLink>
      </div>

      <div role="tabpanel" :aria-labelledby="tabId(active)" class="flex flex-col gap-8">
        <StaffAccountsView v-if="active === 'staff'" />
        <template v-else-if="active === 'imports'">
          <VanillaImportPanel />
          <ImportedPunishmentsPanel />
        </template>
        <CloudNetCataloguePanel v-else-if="active === 'cloudnet'" />
      </div>
    </template>
  </PageShell>
</template>
