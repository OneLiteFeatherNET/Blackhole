<script setup lang="ts">
// Previews an uploaded file. The content endpoint needs the bearer token, which <img> and <video>
// cannot send, so the file is fetched through the API and shown from an object URL that is revoked
// again. Only image and video content types render media; anything else shows just the name.
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '../../api/inject'
import type { Evidence } from '../../api/types'

const props = defineProps<{ item: Evidence }>()

const { t } = useI18n()
const api = useApi()

const src = ref<string | null>(null)
const failed = ref(false)
const contentType = computed(() => props.item.file?.contentType ?? '')
const name = computed(() => props.item.file?.name ?? '')
const isImage = computed(() => contentType.value.startsWith('image/'))
const isVideo = computed(() => contentType.value.startsWith('video/'))

function release(): void {
  if (src.value) URL.revokeObjectURL(src.value)
  src.value = null
}

watch(
  () => props.item.identifier,
  async (identifier) => {
    release()
    failed.value = false
    if (!isImage.value && !isVideo.value) return
    const result = await api.evidenceContent(identifier)
    if (identifier !== props.item.identifier) return // a newer item superseded this answer
    if (result.ok) src.value = URL.createObjectURL(result.data)
    else failed.value = true
  },
  { immediate: true },
)
onBeforeUnmount(release)
</script>

<template>
  <p v-if="failed" role="alert" class="text-body-medium text-error">{{ t('evidence.file.loadFailed') }}</p>
  <img v-else-if="src && isImage" :src="src" :alt="name" class="max-h-96 max-w-full rounded-md-sm object-contain" />
  <div v-else-if="src && isVideo" class="flex flex-col gap-1">
    <video :src="src" controls preload="metadata" :aria-label="name" class="max-h-96 max-w-full rounded-md-sm" />
    <!-- Uploaded recordings have no caption track; say so instead of leaving the gap unexplained. -->
    <p class="text-body-small text-on-surface-variant">{{ t('evidence.file.noCaptions') }}</p>
  </div>
  <p v-else-if="!isImage && !isVideo" class="text-body-medium text-on-surface-variant">{{ t('evidence.file.noPreview', { name }) }}</p>
</template>
