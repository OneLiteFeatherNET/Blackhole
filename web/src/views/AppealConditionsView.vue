<script setup lang="ts">
// The appeal conditions a player confirms before filing. Every team role reads them; only an admin edits. A save
// always creates a new version, nothing is overwritten. The editor is the `new` route; each earlier version has
// its own address. The backend stays authoritative.
import { computed, provide, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useApi } from '../api/inject'
import PageShell from '../components/PageShell.vue'
import AppealTermsEditRoute from '../components/appealterms/AppealTermsEditRoute.vue'
import { CONFIG_PAGE } from '../components/config/configPage'
import MdButton from '../components/md/MdButton.vue'
import MdCard from '../components/md/MdCard.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import ResourceState from '../components/ResourceState.vue'
import TextLink from '../components/TextLink.vue'
import { useResource } from '../composables/useResource'
import { formatDateTime } from '../format'
import { useSessionStore } from '../stores/session'

const { t, locale } = useI18n()
const api = useApi()
const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const current = useResource(() => api.appealConditions())
const versions = useResource(() => api.appealConditionVersions())
const message = ref('')

provide(CONFIG_PAGE, {
  reload: () => Promise.all([current.reload(), versions.reload()]).then(() => undefined),
  async changed(done: string) {
    message.value = done
    await Promise.all([current.reload(), versions.reload()])
  },
})

const creating = computed(() => route.name === 'admin-appeal-terms-new')

function when(iso: string): string {
  return formatDateTime(Date.parse(iso), locale.value)
}
</script>

<template>
  <PageShell :title="t('appealConditions.title')" :description="t('appealConditions.intro')">
    <template #actions>
      <MdButton v-if="session.administrator" data-testid="new-conditions-version" @click="router.push({ name: 'admin-appeal-terms-new' })">
        {{ t('appealConditions.newVersion') }}
      </MdButton>
    </template>

    <ResourceState
      :loading="current.loading.value && current.data.value === null"
      :error="current.error.value"
      :has-data="current.data.value !== null"
      @retry="current.reload"
    >
      <div v-if="current.data.value" class="flex flex-col gap-4">
        <MdCard variant="outlined">
          <h2 class="text-title-medium" data-testid="current-version">
            {{ t('appealConditions.current', { version: current.data.value.version }) }}
          </h2>
          <p class="text-body-small text-on-surface-variant">{{ when(current.data.value.createdAt) }}</p>
          <!-- Plain text only: rendered as text and kept on its line breaks, never as markup. -->
          <p class="mt-3 whitespace-pre-line text-body-large" data-testid="current-text">{{ current.data.value.text }}</p>
        </MdCard>

        <section v-if="versions.data.value" :aria-label="t('appealConditions.history')">
          <h2 class="mb-2 text-title-medium">{{ t('appealConditions.history') }}</h2>
          <ul class="flex flex-col gap-2">
            <li v-for="item in versions.data.value" :key="item.version" data-testid="version-item" :data-version="item.version">
              <details class="rounded-md-md border border-outline-variant p-3">
                <summary class="cursor-pointer text-label-large">
                  {{ t('appealConditions.versionLine', { version: item.version, when: when(item.createdAt) }) }}
                </summary>
                <p class="mt-2 whitespace-pre-line text-body-medium">{{ item.text }}</p>
              </details>
              <TextLink :to="{ name: 'admin-appeal-terms-version', params: { version: item.version } }">
                {{ t('appealConditions.openVersion', { version: item.version }) }}
              </TextLink>
            </li>
          </ul>
        </section>
      </div>
    </ResourceState>

    <AppealTermsEditRoute v-if="creating" />
    <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
  </PageShell>
</template>
