<script setup lang="ts">
// The VPN and hosting login policy and the list sources behind it. Every staff role reads it; only an administrator
// changes a class, in the child route `edit` of the class's own page.
import { RouterLink } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useApi } from '../api/inject'
import PageShell from '../components/PageShell.vue'
import ListSourceCard from '../components/intel/ListSourceCard.vue'
import { LIST_SOURCES } from '../components/intel/listSources'
import { POLICY_CLASSES, policyOf } from '../components/intel/policyRows'
import MdButton from '../components/md/MdButton.vue'
import ResourceState from '../components/ResourceState.vue'
import StatusPill from '../components/StatusPill.vue'
import { useResource } from '../composables/useResource'
import { useSessionStore } from '../stores/session'
import type { IntelPolicy } from '../api/types'

const { t } = useI18n()
const api = useApi()
const session = useSessionStore()

const policies = useResource(() => api.intelPolicies())
const tones = { ALLOW: 'primary', FLAG: 'tertiary', DENY: 'error' } as const
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
        <div v-if="policies.data.value" class="flex flex-col gap-3">
          <section
            v-for="classification in POLICY_CLASSES"
            :key="classification"
            :data-testid="`policy-${classification}`"
            class="flex flex-col gap-3 rounded-md-lg border border-outline-variant p-4 sm:flex-row sm:items-end"
          >
            <div class="min-w-0 flex-1">
              <h3 class="text-title-medium">
                <RouterLink
                  :to="{ name: 'intel-policy-class', params: { classification } }"
                  class="text-primary underline underline-offset-2 focus-ring"
                >
                  {{ t(`intel.classes.${classification}`) }}
                </RouterLink>
              </h3>
              <p class="text-body-medium text-on-surface-variant">{{ t(`intel.classHint.${classification}`) }}</p>
            </div>
            <StatusPill
              :label="t(`intel.policies.${policyOf(policies.data.value, classification)}`)"
              :tone="tones[policyOf(policies.data.value, classification) as IntelPolicy]"
            />
            <MdButton
              v-if="session.administrator"
              as="link"
              :data-testid="`policy-change-${classification}`"
              :to="{ name: 'intel-policy-class-edit', params: { classification } }"
            >
              {{ t('intel.policy.change') }}
            </MdButton>
          </section>
        </div>

        <section aria-labelledby="sources-heading">
          <h2 id="sources-heading" class="mb-3 text-title-medium">{{ t('intel.sources.title') }}</h2>
          <div class="grid gap-3 md:grid-cols-2">
            <!-- No refresh status is reported by the backend yet, so the cards say so instead of guessing. -->
            <ListSourceCard v-for="source in LIST_SOURCES" :key="source.kind" :source="source" :status="null" :last-success-at="null" />
          </div>
        </section>
      </div>
    </ResourceState>
  </PageShell>
</template>
