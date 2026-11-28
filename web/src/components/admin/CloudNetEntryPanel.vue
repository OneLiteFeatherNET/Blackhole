<script setup lang="ts">
// One entry of the CloudNet catalogue by its address (`/admin/cloudnet/:kind/:key`). `kind` is the catalogue's own name for
// the list (tasks, groups, services, proxies); `key` is the entry's name in it. The entry is read from the catalogue, so an
// unknown kind or name shows the not-found text and nothing else. Read only.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { useApi } from '../../api/inject'
import type { CloudNetCatalogue } from '../../api/types'
import { useResource } from '../../composables/useResource'
import CopyLink from '../CopyLink.vue'
import DefinitionList from '../DefinitionList.vue'
import ResourceState from '../ResourceState.vue'
import StatusPill from '../StatusPill.vue'

const KINDS = ['tasks', 'groups', 'services', 'proxies'] as const
type Kind = (typeof KINDS)[number]

const { t } = useI18n()
const api = useApi()
const route = useRoute()
const catalogue = useResource(() => api.cloudnetCatalogue())

const kind = computed(() => (KINDS as readonly string[]).includes(String(route.params.kind)) ? (route.params.kind as Kind) : null)
const key = computed(() => String(route.params.key ?? ''))

/** The entry the address names, or null when the catalogue has no such entry (or the kind is not one of the four). */
function entryOf(list: CloudNetCatalogue | null, which: Kind | null, name: string) {
  if (!list || which === null) return null
  switch (which) {
    case 'tasks': {
      const task = list.tasks.find((candidate) => candidate.name === name)
      return task ? { name, items: [{ term: t('cloudnetCatalogue.columns.groups'), value: (task.groups ?? []).join(', ') || t('cloudnetCatalogue.none') }] } : null
    }
    case 'groups': {
      if (!list.groups.includes(name)) return null
      const tasks = list.tasks.filter((task) => (task.groups ?? []).includes(name)).map((task) => task.name)
      return { name, items: [{ term: t('cloudnetCatalogue.columns.task'), value: tasks.join(', ') || t('cloudnetCatalogue.none') }] }
    }
    case 'services': {
      const service = list.services.find((candidate) => candidate.name === name)
      if (!service) return null
      return {
        name,
        items: [
          { term: t('cloudnetCatalogue.columns.task'), value: service.task || t('cloudnetCatalogue.none') },
          { term: t('cloudnetCatalogue.columns.state'), value: service.running ? t('cloudnetCatalogue.running') : t('cloudnetCatalogue.stopped') },
        ],
      }
    }
    case 'proxies':
      return list.proxies.includes(name) ? { name, items: [] } : null
  }
}

const entry = computed(() => entryOf(catalogue.data.value, kind.value, key.value))
</script>

<template>
  <section aria-labelledby="cloudnet-entry-title" class="flex flex-col gap-4" data-testid="cloudnet-entry">
    <h2 id="cloudnet-entry-title" class="text-title-medium">{{ t(`cloudnetCatalogue.kinds.${kind ?? 'unknown'}`) }}</h2>
    <ResourceState :loading="catalogue.loading.value" :error="catalogue.error.value" :has-data="catalogue.data.value !== null" @retry="catalogue.reload">
      <p v-if="entry === null" role="alert" data-testid="cloudnet-entry-missing" class="text-body-large">{{ t('cloudnetCatalogue.entryMissing') }}</p>
      <template v-else>
        <div>
          <StatusPill :label="entry.name" />
        </div>
        <DefinitionList v-if="entry.items.length > 0" :items="entry.items" />
        <CopyLink />
      </template>
    </ResourceState>
  </section>
</template>
