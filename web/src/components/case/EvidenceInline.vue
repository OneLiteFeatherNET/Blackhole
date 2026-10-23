<script setup lang="ts">
// One evidence item shown inside a timeline entry through the existing evidence components. The
// timeline keeps no copy: the item is read from the evidence route when the user asks for it, which
// the backend audits like any other evidence read.
import { ref, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { Evidence } from '../../api/types'
import DeleteEvidenceDialog from '../evidence/DeleteEvidenceDialog.vue'
import EvidenceItem from '../evidence/EvidenceItem.vue'
import MdButton from '../md/MdButton.vue'

const props = defineProps<{ evidenceId: string }>()

const { t } = useI18n()
const api = useApi()
const regionId = useId()

const open = ref(false)
const loading = ref(false)
const item = ref<Evidence | null>(null)
const error = ref('')
const deleting = ref<Evidence | null>(null)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  const result = await api.evidenceItem(props.evidenceId)
  loading.value = false
  if (result.ok) {
    item.value = result.data
  } else {
    item.value = null
    error.value = result.error.status === 404 ? t('case.evidence.gone') : result.error.message
  }
}

async function toggle(): Promise<void> {
  open.value = !open.value
  if (open.value && item.value === null) await load()
}

async function deleted(): Promise<void> {
  deleting.value = null
  await load()
}
</script>

<template>
  <div class="mt-2">
    <MdButton variant="text" :aria-expanded="open ? 'true' : 'false'" :aria-controls="regionId" data-testid="evidence-toggle" @click="toggle">
      {{ open ? t('case.evidence.hide') : t('case.evidence.show') }}
    </MdButton>
    <div :id="regionId" aria-live="polite">
      <template v-if="open">
        <p v-if="loading" class="text-body-medium text-on-surface-variant">{{ t('state.loading') }}</p>
        <p v-else-if="error" class="text-body-medium text-on-surface-variant" data-testid="evidence-error">{{ error }}</p>
        <ul v-else-if="item" class="mt-2">
          <EvidenceItem :item="item" @delete="deleting = $event" />
        </ul>
      </template>
    </div>
    <DeleteEvidenceDialog :open="deleting !== null" :item="deleting" @close="deleting = null" @done="deleted" />
  </div>
</template>
