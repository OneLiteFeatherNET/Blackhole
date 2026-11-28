<script setup lang="ts">
// One kick by its address (`/admin/kick/:id`): where it was sent, why, and how it went. Read only: a kick cannot be
// acknowledged or changed from here. The id is the kick's own id; no player name or hashed owner is shown in the address.
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { KickResult } from '../../api/types'
import { formatDateTime } from '../../format'
import CopyLink from '../CopyLink.vue'
import DefinitionList from '../DefinitionList.vue'
import MdProgress from '../md/MdProgress.vue'
import KickStatusChip from '../kick/KickStatusChip.vue'

const props = defineProps<{ id: string }>()

const { t, locale } = useI18n()
const api = useApi()

const kick = ref<KickResult | null>(null)
const missing = ref(false)
const error = ref('')
const loading = ref(true)

const items = computed(() => {
  const current = kick.value
  if (!current) return []
  return [
    { term: t('kick.scope.label'), value: t(`kick.scope.${current.scope}`) },
    { term: t('kick.detail.target'), value: current.target ?? '' },
    { term: t('kick.reason.label'), value: current.reason },
    { term: t('kick.detail.by'), value: current.actor ?? '' },
    { term: t('kick.detail.when'), value: formatDateTime(current.createdAt, locale.value) },
    { term: t('kick.detail.delivered'), value: String(current.deliveredCount) },
  ].filter((item) => item.value !== '')
})

async function load(): Promise<void> {
  loading.value = true
  missing.value = false
  error.value = ''
  const result = await api.kick(props.id)
  loading.value = false
  if (result.ok) {
    kick.value = result.data
    return
  }
  kick.value = null
  if (result.error.kind === 'NOT_FOUND') missing.value = true
  else error.value = result.error.message
}

onMounted(load)
watch(() => props.id, load)
</script>

<template>
  <section aria-labelledby="kick-detail-title" class="flex flex-col gap-4" data-testid="kick-detail">
    <h3 id="kick-detail-title" class="text-title-medium">{{ t('kick.detail.title') }}</h3>

    <MdProgress v-if="loading" :label="t('kick.detail.loading')" />
    <p v-else-if="missing" role="alert" data-testid="kick-detail-missing" class="text-body-large">{{ t('kick.detail.notFound') }}</p>
    <p v-else-if="error" role="alert" data-testid="error" class="rounded-md-md bg-error-container p-3 text-on-error-container">{{ error }}</p>
    <template v-else-if="kick">
      <div>
        <KickStatusChip :status="kick.status" />
      </div>
      <DefinitionList :items="items" />
    </template>

    <CopyLink v-if="kick" />
  </section>
</template>
