<script setup lang="ts">
// The evidence of one punishment. `readonly` is the appeal-review variant: it lists, previews and
// deletes, but offers no upload or link form.
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { Evidence } from '../../api/types'
import { useResource } from '../../composables/useResource'
import MdButton from '../md/MdButton.vue'
import MdCheckbox from '../md/MdCheckbox.vue'
import MdSnackbar from '../md/MdSnackbar.vue'
import ResourceState from '../ResourceState.vue'
import StatusPill from '../StatusPill.vue'
import AddLinkDialog from './AddLinkDialog.vue'
import DeleteEvidenceDialog from './DeleteEvidenceDialog.vue'
import EvidenceList from './EvidenceList.vue'
import EvidenceUpload from './EvidenceUpload.vue'

const props = defineProps<{ punishment: string; readonly?: boolean }>()

const { t } = useI18n()
const api = useApi()

// Neither choice is stored: a fresh panel always starts on the first page without lifted evidence.
const page = ref(0)
const showLifted = ref(false)
const addOpen = ref(false)
const deleting = ref<Evidence | null>(null)
const message = ref('')

const list = useResource(() => api.evidence(props.punishment, { includeInactive: showLifted.value, page: page.value }))
// Only valid evidence is counted, whatever the toggle shows.
const count = useResource(() => api.evidenceCount(props.punishment))

watch([page, showLifted], () => void list.reload())
watch(showLifted, () => (page.value = 0))
watch(
  () => props.punishment,
  () => {
    page.value = 0
    showLifted.value = false
    void Promise.all([list.reload(), count.reload()])
  },
)

async function refresh(): Promise<void> {
  await Promise.all([list.reload(), count.reload()])
}

async function finished(done: string): Promise<void> {
  addOpen.value = false
  deleting.value = null
  message.value = done
  await refresh()
}

async function uploaded(): Promise<void> {
  message.value = t('evidence.upload.done')
  await refresh()
}
</script>

<template>
  <section class="flex flex-col gap-4" :aria-label="t('evidence.title')" data-testid="evidence-panel">
    <header class="flex flex-wrap items-center gap-2">
      <h3 class="text-title-medium">{{ t('evidence.title') }}</h3>
      <StatusPill v-if="count.data.value !== null" :label="t('evidence.count', { count: count.data.value })" tone="primary" data-testid="count" />
      <MdButton v-if="!readonly" class="ml-auto" variant="tonal" data-testid="open-add-link" @click="addOpen = true">
        {{ t('evidence.addLink.open') }}
      </MdButton>
    </header>

    <p class="text-body-small text-on-surface-variant">{{ t('evidence.audited') }}</p>

    <EvidenceUpload v-if="!readonly" :punishment="punishment" @uploaded="uploaded" />

    <MdCheckbox v-model="showLifted" :label="t('evidence.showLifted')" :supporting-text="t('evidence.showLiftedHint')" data-testid="show-lifted" />

    <ResourceState :loading="list.loading.value" :error="list.error.value" :has-data="list.data.value !== null" @retry="list.reload">
      <EvidenceList
        v-if="list.data.value"
        :items="list.data.value.items"
        :page="list.data.value.page"
        :total-pages="list.data.value.totalPages"
        @page="page = $event"
        @delete="deleting = $event"
      />
    </ResourceState>

    <AddLinkDialog :open="addOpen" :punishment="punishment" @close="addOpen = false" @done="finished" />
    <DeleteEvidenceDialog :open="deleting !== null" :item="deleting" @close="deleting = null" @done="finished" />
    <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
  </section>
</template>
