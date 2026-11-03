<script setup lang="ts">
// One section at its own address (`/admin/sections/:key`). Edit and disable are child routes of this address; the
// copy-link button copies it. An unknown key shows the not-found text and no data.
import { computed, provide, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useApi } from '../api/inject'
import Breadcrumbs from '../components/Breadcrumbs.vue'
import CopyLink from '../components/CopyLink.vue'
import DefinitionList from '../components/DefinitionList.vue'
import PageShell from '../components/PageShell.vue'
import ResourceState from '../components/ResourceState.vue'
import StatusPill from '../components/StatusPill.vue'
import TextLink from '../components/TextLink.vue'
import { CONFIG_PAGE } from '../components/config/configPage'
import SectionDisableRoute from '../components/sections/SectionDisableRoute.vue'
import SectionEditRoute from '../components/sections/SectionEditRoute.vue'
import MdButton from '../components/md/MdButton.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import { useResource } from '../composables/useResource'
import { formatDuration } from '../format'
import { useSessionStore } from '../stores/session'

const props = defineProps<{ sectionKey: string }>()

const { t, locale } = useI18n()
const api = useApi()
const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const section = useResource(() => api.section(props.sectionKey))
const message = ref('')

provide(CONFIG_PAGE, {
  reload: () => section.reload(),
  async changed(done: string) {
    message.value = done
    await section.reload()
  },
})

const missing = computed(() => section.error.value?.kind === 'NOT_FOUND')
const child = computed(() => (route.name === 'admin-section-edit' ? 'edit' : route.name === 'admin-section-disable' ? 'disable' : null))
</script>

<template>
  <PageShell :title="section.data.value?.displayName ?? props.sectionKey">
    <template #breadcrumbs>
      <Breadcrumbs :items="[{ label: t('nav.sections'), to: '/admin/sections' }, { label: props.sectionKey }]" />
    </template>
    <template #actions>
      <CopyLink />
      <template v-if="session.administrator && section.data.value">
        <MdButton variant="tonal" data-testid="edit-section" @click="router.push({ name: 'admin-section-edit', params: { key: props.sectionKey } })">
          {{ t('sections.edit') }}
        </MdButton>
        <MdButton
          v-if="section.data.value.enabled"
          variant="tonal"
          data-testid="disable-section"
          @click="router.push({ name: 'admin-section-disable', params: { key: props.sectionKey } })"
        >
          {{ t('sections.disable.open') }}
        </MdButton>
      </template>
    </template>

    <div v-if="missing" data-testid="not-found" class="flex flex-col gap-2">
      <p>{{ t('configDetail.notFound') }}</p>
      <TextLink :to="{ name: 'admin-sections' }">{{ t('configDetail.backToList') }}</TextLink>
    </div>
    <ResourceState
      v-else
      :loading="section.loading.value && section.data.value === null"
      :error="section.error.value"
      :has-data="section.data.value !== null"
      @retry="section.reload"
    >
      <template v-if="section.data.value">
        <DefinitionList
          :items="[
            { term: t('sections.columns.section'), value: section.data.value.key },
            { term: t('sections.columns.enforcement'), value: t(`enforcement.${section.data.value.enforcement}`) },
            { term: t('sections.columns.servers'), value: section.data.value.servers.join(', ') || '-' },
            { term: t('sections.columns.scores'), value: `${t('sections.base')} ${section.data.value.baseScore} · ${t('sections.pardon')} ${section.data.value.pardonScore} · ${t('sections.decay')} ${section.data.value.decayPerDay}` },
            {
              term: t('sections.columns.thresholds'),
              value: `${t('sections.soft')} ${section.data.value.softThreshold ?? '-'}${section.data.value.softDuration ? ` (${formatDuration(section.data.value.softDuration, locale)})` : ''} · ${t('sections.permanent')} ${section.data.value.permanentThreshold}`,
            },
          ]"
        />
      </template>
      <StatusPill v-if="section.data.value && !section.data.value.enabled" class="mt-4" :label="t('sections.disabledPill')" />
    </ResourceState>

    <SectionEditRoute v-if="child === 'edit' && section.data.value" />
    <SectionDisableRoute v-if="child === 'disable' && section.data.value" />
    <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
  </PageShell>
</template>
