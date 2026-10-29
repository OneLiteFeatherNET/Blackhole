<script setup lang="ts">
// The VPN and hosting login policy and the list sources behind it. Every staff role reads it; only an administrator changes it.
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../api/inject'
import PageShell from '../components/PageShell.vue'
import ListSourceCard from '../components/intel/ListSourceCard.vue'
import PolicyForm from '../components/intel/PolicyForm.vue'
import { LIST_SOURCES } from '../components/intel/listSources'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import ResourceState from '../components/ResourceState.vue'
import { useResource } from '../composables/useResource'
import { useSessionStore } from '../stores/session'

const { t } = useI18n()
const api = useApi()
const session = useSessionStore()

const policies = useResource(() => api.intelPolicies())
const message = ref('')

async function saved(text: string): Promise<void> {
  message.value = text
  await policies.reload()
}
</script>

<template>
  <PageShell :title="t('intel.policy.title')" :description="t('intel.policy.intro')">
    <ResourceState
      :loading="policies.loading.value && policies.data.value === null"
      :error="policies.error.value"
      :has-data="policies.data.value !== null"
      @retry="policies.reload"
    >
      <div class="flex flex-col gap-6">
        <PolicyForm v-if="policies.data.value" :policies="policies.data.value" :administrator="session.administrator" @saved="saved" />

        <section aria-labelledby="sources-heading">
          <h2 id="sources-heading" class="mb-3 text-title-medium">{{ t('intel.sources.title') }}</h2>
          <div class="grid gap-3 md:grid-cols-2">
            <!-- No refresh status is reported by the backend yet, so the cards say so instead of guessing. -->
            <ListSourceCard v-for="source in LIST_SOURCES" :key="source.kind" :source="source" :status="null" :last-success-at="null" />
          </div>
        </section>
      </div>
    </ResourceState>
    <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
  </PageShell>
</template>
