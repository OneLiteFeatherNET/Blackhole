<script setup lang="ts">
import { inject, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter, type RouteLocationRaw } from 'vue-router'
import MdButton from './md/MdButton.vue'
import { browserClipboard, CLIPBOARD_KEY } from './copyLink'

// Copies the absolute address of the current view, with its query and fragment, or of the item given in `to` (an
// item of the page, such as a note or a message). Nothing else is copied: no Ban ID, name or IP is part of an address.
const props = defineProps<{ to?: RouteLocationRaw }>()
const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const clipboard = inject(CLIPBOARD_KEY, browserClipboard())
const outcome = ref<'idle' | 'copied' | 'failed'>('idle')

function absoluteLink(): string {
  const target = props.to ?? route.fullPath
  return new URL(router.resolve(target).href, window.location.origin).href
}

async function copy(): Promise<void> {
  try {
    await clipboard.writeText(absoluteLink())
    outcome.value = 'copied'
  } catch {
    outcome.value = 'failed'
  }
}
</script>

<template>
  <div class="inline-flex flex-wrap items-center gap-2">
    <MdButton variant="tonal" data-testid="copy-link" @click="copy">{{ t('copyLink.button') }}</MdButton>
    <span role="status" data-testid="copy-link-status" class="text-body-medium text-on-surface-variant">
      <template v-if="outcome === 'copied'">{{ t('copyLink.copied') }}</template>
      <template v-else-if="outcome === 'failed'">{{ t('copyLink.failed') }}</template>
    </span>
  </div>
</template>
