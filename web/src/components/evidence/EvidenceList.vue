<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { Evidence } from '../../api/types'
import MdButton from '../md/MdButton.vue'
import EvidenceItem from './EvidenceItem.vue'

defineProps<{ items: Evidence[]; page: number; totalPages: number }>()
const emit = defineEmits<{ page: [page: number]; delete: [item: Evidence] }>()

const { t } = useI18n()
</script>

<template>
  <p v-if="items.length === 0" class="text-body-large text-on-surface-variant" data-testid="empty">{{ t('evidence.empty') }}</p>
  <ul v-else class="flex flex-col gap-3">
    <EvidenceItem v-for="item in items" :key="item.identifier" :item="item" @delete="emit('delete', $event)" />
  </ul>
  <nav v-if="totalPages > 1" :aria-label="t('evidence.paging.label')" class="mt-3 flex items-center justify-end gap-2">
    <MdButton variant="text" data-testid="previous" :disabled="page <= 0" @click="emit('page', page - 1)">{{ t('evidence.paging.previous') }}</MdButton>
    <span class="text-body-medium text-on-surface-variant" aria-live="polite">{{ t('evidence.paging.position', { page: page + 1, total: totalPages }) }}</span>
    <MdButton variant="text" data-testid="next" :disabled="page + 1 >= totalPages" @click="emit('page', page + 1)">{{ t('evidence.paging.next') }}</MdButton>
  </nav>
</template>
