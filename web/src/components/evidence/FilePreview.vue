<script setup lang="ts">
// Previews an uploaded file straight from the backend's content endpoint. Only image and video
// content types render media; anything else shows just the name.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { Evidence } from '../../api/types'

const props = defineProps<{ item: Evidence }>()

const { t } = useI18n()
const api = useApi()

const src = computed(() => api.evidenceContentUrl(props.item.identifier))
const contentType = computed(() => props.item.file?.contentType ?? '')
const name = computed(() => props.item.file?.name ?? '')
</script>

<template>
  <img v-if="contentType.startsWith('image/')" :src="src" :alt="name" loading="lazy" class="max-h-96 max-w-full rounded-md-sm object-contain" />
  <div v-else-if="contentType.startsWith('video/')" class="flex flex-col gap-1">
    <video :src="src" controls preload="metadata" :aria-label="name" class="max-h-96 max-w-full rounded-md-sm" />
    <!-- Uploaded recordings have no caption track; say so instead of leaving the gap unexplained. -->
    <p class="text-body-small text-on-surface-variant">{{ t('evidence.file.noCaptions') }}</p>
  </div>
  <p v-else class="text-body-medium text-on-surface-variant">{{ t('evidence.file.noPreview', { name }) }}</p>
</template>
