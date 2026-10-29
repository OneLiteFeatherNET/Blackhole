<script setup lang="ts">
// Exemptions from the VPN and hosting policy. Moderators and administrators add them; viewers only read this page.
// The backend has no route to list or remove exemptions yet, so this page shows the ones added in this visit.
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { AllowListEntry } from '../api/types'
import PageShell from '../components/PageShell.vue'
import AllowListForm from '../components/intel/AllowListForm.vue'
import { useSessionStore } from '../stores/session'

const { t } = useI18n()
const session = useSessionStore()

const added = ref<AllowListEntry[]>([])

function onAdded(entry: AllowListEntry): void {
  added.value = [entry, ...added.value]
}
</script>

<template>
  <PageShell :title="t('intel.allowList.title')" :description="t('intel.allowList.intro')">
    <AllowListForm v-if="session.moderator" :administrator="session.administrator" :entries="added" @added="onAdded" />
    <p v-else data-testid="allow-list-readonly" class="rounded-md-md bg-surface-container-high p-3 text-on-surface">
      {{ t('intel.allowList.readonly') }}
    </p>
  </PageShell>
</template>
