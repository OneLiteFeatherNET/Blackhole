<script setup lang="ts">
// One held-back automatic punishment, read by its id: the player (short hash), the section, the track and the score.
// A decision is the `decide` child route: the header's actions navigate there with `?action=apply|dismiss`, the dialog
// opens over this page and sends the decision only when it is confirmed there. Back or Cancel sends nothing.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useApi } from '../api/inject'
import type { PillTone } from '../components/case/caseTones'
import Breadcrumbs from '../components/Breadcrumbs.vue'
import DefinitionList from '../components/DefinitionList.vue'
import EntityHeader from '../components/entity/EntityHeader.vue'
import type { EntityAction, EntityStateView } from '../components/entity/entityHeader'
import ExemptionBadge from '../components/exemption/ExemptionBadge.vue'
import ExemptionDecisionDialog, { type ExemptionDecision } from '../components/exemption/ExemptionDecisionDialog.vue'
import PageShell from '../components/PageShell.vue'
import ResourceState from '../components/ResourceState.vue'
import TextLink from '../components/TextLink.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import { useResource } from '../composables/useResource'
import { formatDateTime, shortHash } from '../format'
import { useRoutedDialog } from '../router/routedDialog'

const PAGE = 'admin-exemption-review'
const DECISION = 'admin-exemption-review-decide'
const STATE_TONE: Record<string, PillTone> = { OPEN: 'primary', APPLIED: 'success', DISMISSED: 'neutral' }

const props = defineProps<{ id: string }>()

interface DefinitionItem {
  key?: string
  term: string
  value?: string
}

const { t, locale } = useI18n()
const api = useApi()
const route = useRoute()
const router = useRouter()

const review = useResource(() => api.exemptionReview(props.id))
const message = ref('')
// The same component serves the page and its decision address, so a different id is read again.
watch(
  () => props.id,
  () => review.reload(),
)

const current = computed(() => review.data.value)
const dialog = useRoutedDialog({ parent: PAGE, child: DECISION })
const action = computed<ExemptionDecision | null>(() => {
  const raw = route.query.action
  return raw === 'apply' || raw === 'dismiss' ? raw : null
})
// The dialog opens only for a read review that is still open and a known decision: never an empty or a decided one.
const deciding = computed(() => dialog.open.value && current.value?.state === 'OPEN' && action.value !== null)

const crumbs = computed(() => [{ label: t('nav.exemptionReviews'), to: '/admin/exemption-reviews' }, { label: `#${props.id.slice(0, 8)}` }])

const states = computed<EntityStateView[]>(() => {
  const review = current.value
  if (!review) return []
  return [{ label: t(`exemptionReviews.state.${review.state}`), tone: STATE_TONE[review.state] ?? 'neutral', testid: 'exemption-review-state' }]
})

// The actions navigate to the decision route; they send nothing. Applying is the primary action, dismissing the overflow.
function decisionTo(choice: ExemptionDecision) {
  return { name: DECISION, params: { id: props.id }, query: { action: choice } }
}
const primary = computed<EntityAction | null>(() =>
  current.value?.state === 'OPEN' ? { key: 'apply', label: t('exemptionReviews.apply.open'), testid: 'apply-review', to: decisionTo('apply') } : null,
)
const overflow = computed<EntityAction[]>(() =>
  current.value?.state === 'OPEN' ? [{ key: 'dismiss', label: t('exemptionReviews.dismiss.open'), testid: 'dismiss-review', to: decisionTo('dismiss') }] : [],
)

const facts = computed<DefinitionItem[]>(() => {
  const review = current.value
  if (!review) return []
  const items: DefinitionItem[] = [
    { key: 'player', term: t('exemptionReviews.columns.player') },
    { term: t('exemptionReviews.columns.section'), value: review.section },
    { term: t('exemptionReviews.columns.track'), value: review.tier },
    { term: t('exemptionReviews.columns.score'), value: String(review.scoreAfter) },
    { term: t('exemptionReviews.columns.crossed'), value: formatDateTime(review.crossedAt, locale.value) },
    { term: t('exemptionReviews.detail.state'), value: t(`exemptionReviews.state.${review.state}`) },
  ]
  if (review.justification) items.push({ term: t('exemptionReviews.detail.justification'), value: review.justification })
  return items
})

/** Leaves the decision address: Back when this page was the previous entry, otherwise this page replaces it. */
async function closeDecision(): Promise<void> {
  const page = { name: PAGE, params: { id: props.id } }
  if (router.options.history.state.back === router.resolve(page).fullPath) {
    router.back()
    return
  }
  await router.replace(page)
}

/** The decision is sent; the decision address is left and the review is read again, so the page shows the new state. */
async function decided(text: string): Promise<void> {
  message.value = text
  await closeDecision()
  await review.reload()
}
</script>

<template>
  <PageShell :title="t('exemptionReviews.detail.title')">
    <template #header>
      <EntityHeader
        v-if="current"
        :type-label="t('exemptionReviews.detail.title')"
        :heading="current.section"
        :short-id="props.id.slice(0, 8)"
        :states="states"
        :primary="primary"
        :overflow="overflow"
        :crumbs="crumbs"
      />
      <header v-else class="flex flex-col gap-2">
        <Breadcrumbs :items="crumbs" />
        <h1 class="text-headline-small text-on-surface">{{ t('exemptionReviews.detail.title') }}</h1>
      </header>
    </template>

    <ResourceState :loading="review.loading.value" :error="review.error.value" :has-data="current !== null" @retry="review.reload">
      <DefinitionList v-if="current" :items="facts">
        <template #value="{ item }">
          <div v-if="item.key === 'player'" class="flex flex-wrap items-center gap-2">
            <TextLink :to="`/players/${current.owner}`" :title="current.owner">{{ shortHash(current.owner) }}</TextLink>
            <ExemptionBadge :owner="current.owner" />
          </div>
          <template v-else>{{ item.value }}</template>
        </template>
      </DefinitionList>
    </ResourceState>

    <div v-if="review.error.value?.kind === 'NOT_FOUND'" data-testid="exemption-review-not-found" class="text-body-medium">
      <p>{{ t('exemptionReviews.detail.notFound') }}</p>
      <TextLink class="mt-3 inline-block" :to="{ name: 'admin-exemption-reviews' }">{{ t('exemptionReviews.detail.back') }}</TextLink>
    </div>
  </PageShell>

  <ExemptionDecisionDialog :open="deciding" :review="current" :action="action" @close="closeDecision" @done="decided" />
  <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
</template>
