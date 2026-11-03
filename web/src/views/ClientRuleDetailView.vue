<script setup lang="ts">
// One client rule at its own address (`/admin/client-rules/:key`). The tab is in the query (`?tab=details|hits`);
// the hits are read only when their tab is open. Edit is a child route. An unknown key shows the not-found text.
import { computed, provide, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useApi } from '../api/inject'
import type { ClientRuleHit } from '../api/types'
import Breadcrumbs from '../components/Breadcrumbs.vue'
import CopyLink from '../components/CopyLink.vue'
import DefinitionList from '../components/DefinitionList.vue'
import PageShell from '../components/PageShell.vue'
import ClientRuleEditRoute from '../components/clientrules/ClientRuleEditRoute.vue'
import ClientRuleHitTable from '../components/clientrules/ClientRuleHitTable.vue'
import { CONFIG_PAGE } from '../components/config/configPage'
import MdButton from '../components/md/MdButton.vue'
import MdProgress from '../components/md/MdProgress.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import ResourceState from '../components/ResourceState.vue'
import TextLink from '../components/TextLink.vue'
import { useResource } from '../composables/useResource'
import { useSessionStore } from '../stores/session'

const props = defineProps<{ ruleKey: string }>()

const { t } = useI18n()
const api = useApi()
const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const rule = useResource(() => api.clientRule(props.ruleKey))
const message = ref('')

provide(CONFIG_PAGE, {
  reload: () => rule.reload(),
  async changed(done: string) {
    message.value = done
    await rule.reload()
  },
})

const missing = computed(() => rule.error.value?.kind === 'NOT_FOUND')
const tab = computed(() => (route.query.tab === 'hits' ? 'hits' : 'details'))
const editing = computed(() => route.name === 'admin-client-rule-edit')

const hits = ref<ClientRuleHit[] | null>(null)
const hitsLoading = ref(false)
const hitsError = ref('')

// The hits are read once the rule is known and its tab is open; a change of rule reads its own hits.
async function loadHits(key: string): Promise<void> {
  hits.value = null
  hitsError.value = ''
  hitsLoading.value = true
  const result = await api.clientRuleHits(key)
  if (key !== props.ruleKey) return
  hitsLoading.value = false
  if (result.ok) hits.value = result.data
  else hitsError.value = result.error.message
}

watch(
  () => (tab.value === 'hits' && rule.data.value !== null ? props.ruleKey : null),
  (key) => {
    if (key !== null) void loadHits(key)
  },
  { immediate: true },
)

function matchText(): string {
  const current = rule.data.value
  if (!current) return ''
  if (current.kind === 'PROTOCOL') return `${current.protocolMin ?? '?'} - ${current.protocolMax ?? '?'}`
  return current.patterns.join(', ')
}
</script>

<template>
  <PageShell :title="rule.data.value?.displayName ?? props.ruleKey">
    <template #breadcrumbs>
      <Breadcrumbs :items="[{ label: t('nav.clientRules'), to: '/admin/client-rules' }, { label: props.ruleKey }]" />
    </template>
    <template #actions>
      <CopyLink />
      <MdButton
        v-if="session.administrator && rule.data.value"
        variant="tonal"
        data-testid="edit-client-rule"
        @click="router.push({ name: 'admin-client-rule-edit', params: { key: props.ruleKey } })"
      >
        {{ t('clientRules.edit') }}
      </MdButton>
    </template>

    <div v-if="missing" data-testid="not-found" class="flex flex-col gap-2">
      <p>{{ t('configDetail.notFound') }}</p>
      <TextLink :to="{ name: 'admin-client-rules' }">{{ t('configDetail.backToList') }}</TextLink>
    </div>
    <ResourceState
      v-else
      :loading="rule.loading.value && rule.data.value === null"
      :error="rule.error.value"
      :has-data="rule.data.value !== null"
      @retry="rule.reload"
    >
      <template v-if="rule.data.value">
        <nav :aria-label="t('clientRules.tabs')" class="mb-4 flex gap-2">
          <RouterLink
            :to="{ query: { tab: 'details' } }"
            data-testid="tab-details"
            :aria-current="tab === 'details' ? 'page' : undefined"
            class="inline-flex min-h-12 items-center rounded-md-full px-4 text-label-large"
            :class="tab === 'details' ? 'bg-secondary-container text-on-secondary-container' : 'text-primary'"
          >
            {{ t('clientRules.tab.details') }}
          </RouterLink>
          <RouterLink
            :to="{ query: { tab: 'hits' } }"
            data-testid="tab-hits"
            :aria-current="tab === 'hits' ? 'page' : undefined"
            class="inline-flex min-h-12 items-center rounded-md-full px-4 text-label-large"
            :class="tab === 'hits' ? 'bg-secondary-container text-on-secondary-container' : 'text-primary'"
          >
            {{ t('clientRules.tab.hits') }}
          </RouterLink>
        </nav>

        <DefinitionList
          v-if="tab === 'details'"
          :items="[
            { term: t('clientRules.columns.rule'), value: rule.data.value.key },
            { term: t('clientRules.columns.match'), value: `${t(`clientRules.kind.${rule.data.value.kind}`)} ${matchText()}` },
            { term: t('clientRules.columns.action'), value: t(`clientRules.action.${rule.data.value.action}`) },
            { term: t('clientRules.columns.hits'), value: t('clientRules.hitCount', { n: rule.data.value.hitCount ?? 0 }) },
          ]"
        />

        <section v-else data-testid="hits-section" :aria-label="t('clientRules.hits.title', { key: props.ruleKey })" class="flex flex-col gap-3">
          <p class="text-body-medium text-on-surface-variant">{{ t('clientRules.hits.hint') }}</p>
          <div aria-live="polite" class="flex flex-col gap-3">
            <MdProgress v-if="hitsLoading" :label="t('state.loading')" />
            <p v-else-if="hitsError" data-testid="hits-error" role="alert" class="rounded-md-md bg-error-container p-3 text-on-error-container">{{ hitsError }}</p>
            <p v-else-if="hits && hits.length === 0" data-testid="hits-empty" class="text-body-large text-on-surface-variant">{{ t('clientRules.hits.empty') }}</p>
            <ClientRuleHitTable v-else-if="hits" :hits="hits" by="player" :label="t('clientRules.hits.title', { key: props.ruleKey })" />
          </div>
        </section>
      </template>
    </ResourceState>

    <ClientRuleEditRoute v-if="editing && rule.data.value" />
    <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
  </PageShell>
</template>
