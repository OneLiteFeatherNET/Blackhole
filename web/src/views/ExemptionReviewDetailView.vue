<script setup lang="ts">
// One held-back automatic punishment, read by its id: the player (short hash), the section, the track and the score.
// A decision is the `decide` child route: the dialog opens over this page with `?action=apply|dismiss`, and sends the
// decision only when it is confirmed there. Back or Cancel leaves the decision without sending anything.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useApi } from '../api/inject'
import Breadcrumbs from '../components/Breadcrumbs.vue'
import CopyLink from '../components/CopyLink.vue'
import DefinitionList from '../components/DefinitionList.vue'
import ExemptionBadge from '../components/exemption/ExemptionBadge.vue'
import ExemptionDecisionDialog, { type ExemptionDecision } from '../components/exemption/ExemptionDecisionDialog.vue'
import PageShell from '../components/PageShell.vue'
import ResourceState from '../components/ResourceState.vue'
import TextLink from '../components/TextLink.vue'
import MdButton from '../components/md/MdButton.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import { useResource } from '../composables/useResource'
import { formatDateTime, shortHash } from '../format'
import { useRoutedDialog } from '../router/routedDialog'

const PAGE = 'admin-exemption-review'
const DECISION = 'admin-exemption-review-decide'

const props = defineProps<{ id: string }>()

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

interface DefinitionItem {
  key?: string
  term: string
  value?: string
}

const current = computed(() => review.data.value)
const dialog = useRoutedDialog({ parent: PAGE, child: DECISION })
const action = computed<ExemptionDecision | null>(() => {
  const raw = route.query.action
  return raw === 'apply' || raw === 'dismiss' ? raw : null
})
// The dialog opens only for a read review that is still open and a known decision: never an empty or a decided one.
const deciding = computed(() => dialog.open.value && current.value?.state === 'OPEN' && action.value !== null)

const crumbs = computed(() => [{ label: t('nav.exemptionReviews'), to: '/admin/exemption-reviews' }, { label: `#${props.id.slice(0, 8)}` }])

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

/** Opens the decision as the child route; the dialog there sends nothing until it is confirmed. */
async function decide(choice: ExemptionDecision): Promise<void> {
  await router.push({ name: DECISION, params: { id: props.id }, query: { action: choice } })
}

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
    <Breadcrumbs :items="crumbs" />

    <ResourceState class="mt-4" :loading="review.loading.value" :error="review.error.value" :has-data="current !== null" @retry="review.reload">
      <template v-if="current">
        <DefinitionList :items="facts" class="mb-6">
          <template #value="{ item }">
            <div v-if="item.key === 'player'" class="flex flex-wrap items-center gap-2">
              <TextLink :to="`/players/${current.owner}`" :title="current.owner">{{ shortHash(current.owner) }}</TextLink>
              <ExemptionBadge :owner="current.owner" />
            </div>
            <template v-else>{{ item.value }}</template>
          </template>
        </DefinitionList>

        <div class="flex flex-wrap items-center gap-3">
          <template v-if="current.state === 'OPEN'">
            <MdButton variant="text" data-testid="dismiss-review" @click="decide('dismiss')">{{ t('exemptionReviews.dismiss.open') }}</MdButton>
            <MdButton variant="tonal" data-testid="apply-review" @click="decide('apply')">{{ t('exemptionReviews.apply.open') }}</MdButton>
          </template>
          <CopyLink />
        </div>
      </template>
    </ResourceState>

    <div v-if="review.error.value?.kind === 'NOT_FOUND'" data-testid="exemption-review-not-found" class="text-body-medium">
      <p>{{ t('exemptionReviews.detail.notFound') }}</p>
      <TextLink class="mt-3 inline-block" :to="{ name: 'admin-exemption-reviews' }">{{ t('exemptionReviews.detail.back') }}</TextLink>
    </div>
  </PageShell>

  <ExemptionDecisionDialog :open="deciding" :review="current" :action="action" @close="closeDecision" @done="decided" />
  <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
</template>
