<script setup lang="ts">
// One version of the appeal conditions at its own address (`/admin/appeal-terms/versions/:version`), read by its
// number. Read-only for every team role. An unknown version shows the not-found text and no text.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../api/inject'
import Breadcrumbs from '../components/Breadcrumbs.vue'
import CopyLink from '../components/CopyLink.vue'
import PageShell from '../components/PageShell.vue'
import ResourceState from '../components/ResourceState.vue'
import TextLink from '../components/TextLink.vue'
import { useResource } from '../composables/useResource'
import { formatDateTime } from '../format'

const props = defineProps<{ version: number }>()

const { t, locale } = useI18n()
const api = useApi()

const terms = useResource(() => api.appealConditionVersion(props.version))

const missing = computed(() => terms.error.value?.kind === 'NOT_FOUND')
</script>

<template>
  <PageShell :title="t('appealConditions.versionTitle', { version: props.version })">
    <template #breadcrumbs>
      <Breadcrumbs :items="[{ label: t('nav.appealTerms'), to: '/admin/appeal-terms' }, { label: `v${props.version}` }]" />
    </template>
    <template #actions>
      <CopyLink />
    </template>

    <div v-if="missing" data-testid="not-found" class="flex flex-col gap-2">
      <p>{{ t('configDetail.notFound') }}</p>
      <TextLink :to="{ name: 'admin-appeal-terms' }">{{ t('configDetail.backToList') }}</TextLink>
    </div>
    <ResourceState
      v-else
      :loading="terms.loading.value && terms.data.value === null"
      :error="terms.error.value"
      :has-data="terms.data.value !== null"
      @retry="terms.reload"
    >
      <template v-if="terms.data.value">
        <p class="text-body-small text-on-surface-variant" data-testid="version-when">
          {{ formatDateTime(Date.parse(terms.data.value.createdAt), locale) }}
        </p>
        <!-- Plain text only: rendered as text and kept on its line breaks, never as markup. -->
        <p class="mt-3 whitespace-pre-line text-body-large" data-testid="version-text">{{ terms.data.value.text }}</p>
      </template>
    </ResourceState>
  </PageShell>
</template>
