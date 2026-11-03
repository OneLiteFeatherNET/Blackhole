<script setup lang="ts">
// One evidence item at `/evidence/:id`: what it is, its file or text, the case it belongs to, and the copy of its address.
// Deleting it is a child route (`/evidence/:id/delete`) that the page renders over itself.
import { computed, provide, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterView, useRoute, useRouter } from 'vue-router'
import { useApi } from '../api/inject'
import Breadcrumbs from '../components/Breadcrumbs.vue'
import CopyLink from '../components/CopyLink.vue'
import PageShell from '../components/PageShell.vue'
import ResourceState from '../components/ResourceState.vue'
import TextLink from '../components/TextLink.vue'
import EvidenceItem from '../components/evidence/EvidenceItem.vue'
import { EVIDENCE_ITEM_KEY } from '../components/evidence/evidenceItemContext'
import { kindInfo } from '../components/evidence/evidenceKind'
import { useResource } from '../composables/useResource'

const props = defineProps<{ id: string }>()

const { t } = useI18n()
const api = useApi()
const route = useRoute()
const router = useRouter()

const detail = useResource(() => api.evidenceItem(props.id))
watch(
  () => props.id,
  () => void detail.reload(),
)
provide(EVIDENCE_ITEM_KEY, { item: detail.data })

const title = computed(() => (detail.data.value ? t(kindInfo(detail.data.value.kind).labelKey) : t('evidence.title')))
const crumbs = computed(() => [{ label: t('nav.cases'), to: '/cases' }, { label: t('evidence.title') }])

function openDelete(): void {
  void router.push({ name: 'evidence-delete', params: { id: props.id }, query: route.query })
}
</script>

<template>
  <PageShell :title="title">
    <template #header>
      <Breadcrumbs :items="crumbs" />
      <div class="flex flex-wrap items-center justify-between gap-3">
        <h1 class="text-headline-small text-on-surface">{{ title }}</h1>
        <CopyLink />
      </div>
    </template>

    <ResourceState :loading="detail.loading.value" :error="detail.error.value" :has-data="detail.data.value !== null" @retry="detail.reload">
      <div v-if="detail.data.value" class="flex flex-col gap-4">
        <p v-if="detail.data.value.punishmentIdentifier" class="flex flex-wrap items-center gap-3" data-testid="evidence-case">
          <TextLink :to="`/cases/${detail.data.value.punishmentIdentifier}`" data-testid="evidence-case-link">{{ t('evidence.page.case') }}</TextLink>
        </p>
        <ul class="flex flex-col gap-3">
          <EvidenceItem :item="detail.data.value" @delete="openDelete" />
        </ul>
      </div>
    </ResourceState>

    <RouterView />
  </PageShell>
</template>
