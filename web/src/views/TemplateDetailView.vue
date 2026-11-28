<script setup lang="ts">
// One template at its own address (`/admin/templates/:id`). Its edit dialog is a child route; the copy-link
// button copies this address. An unknown identifier shows the not-found text and no data.
import { computed, provide, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useApi } from '../api/inject'
import Breadcrumbs from '../components/Breadcrumbs.vue'
import CopyLink from '../components/CopyLink.vue'
import DefinitionList from '../components/DefinitionList.vue'
import PageShell from '../components/PageShell.vue'
import ResourceState from '../components/ResourceState.vue'
import TextLink from '../components/TextLink.vue'
import { CONFIG_PAGE } from '../components/config/configPage'
import MdButton from '../components/md/MdButton.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import TemplateEditRoute from '../components/templates/TemplateEditRoute.vue'
import { useResource } from '../composables/useResource'
import { formatDuration } from '../format'
import { useSessionStore } from '../stores/session'

const props = defineProps<{ id: string }>()

const { t, locale } = useI18n()
const api = useApi()
const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const template = useResource(() => api.template(props.id))
const message = ref('')

provide(CONFIG_PAGE, {
  reload: () => template.reload(),
  async changed(done: string) {
    message.value = done
    await template.reload()
  },
})

const missing = computed(() => template.error.value?.kind === 'NOT_FOUND')
const editing = computed(() => route.name === 'admin-template-edit')
</script>

<template>
  <PageShell :title="template.data.value?.key ?? t('templates.title')">
    <template #breadcrumbs>
      <Breadcrumbs :items="[{ label: t('nav.templates'), to: '/admin/templates' }, { label: props.id.slice(0, 8) }]" />
    </template>
    <template #actions>
      <CopyLink />
      <MdButton v-if="session.administrator && template.data.value" variant="tonal" data-testid="edit-template" @click="router.push({ name: 'admin-template-edit', params: { id: props.id } })">
        {{ t('templates.edit') }}
      </MdButton>
    </template>

    <div v-if="missing" data-testid="not-found" class="flex flex-col gap-2">
      <p>{{ t('configDetail.notFound') }}</p>
      <TextLink :to="{ name: 'admin-templates' }">{{ t('configDetail.backToList') }}</TextLink>
    </div>
    <ResourceState
      v-else
      :loading="template.loading.value && template.data.value === null"
      :error="template.error.value"
      :has-data="template.data.value !== null"
      @retry="template.reload"
    >
      <template v-if="template.data.value">
        <DefinitionList
          :items="[
            { term: t('templates.columns.key'), value: template.data.value.key },
            { term: t('templates.columns.reason'), value: template.data.value.reason },
            { term: t('templates.editor.section'), value: template.data.value.section },
            { term: t('templates.editor.group'), value: template.data.value.escalationGroup || '-' },
            { term: t('templates.editor.publicReason'), value: template.data.value.publicReason || '-' },
          ]"
        />
        <section class="mt-6" :aria-label="t('templates.columns.steps')">
          <h2 class="mb-2 text-title-medium">{{ t('templates.columns.steps') }}</h2>
          <p class="text-label-large">{{ t('templates.stepCount', { count: template.data.value.steps.length }, template.data.value.steps.length) }}</p>
          <ol class="mt-1 flex flex-col gap-1 text-body-small text-on-surface-variant">
            <li v-for="(step, index) in template.data.value.steps" :key="index">
              {{
                t('templates.stepLine', {
                  n: index + 1,
                  duration: step.kind && step.kind !== 'PUNISH' ? t(`templates.editor.kinds.${step.kind}`) : formatDuration(step.duration ?? '', locale),
                  cost: step.eloCost,
                })
              }}
            </li>
          </ol>
        </section>
      </template>
    </ResourceState>

    <TemplateEditRoute v-if="editing && template.data.value" />
    <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
  </PageShell>
</template>
