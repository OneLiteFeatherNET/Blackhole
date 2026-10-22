<script setup lang="ts">
// An external link. Only http(s) values become anchors; anything else (a stored javascript: or
// data: value) is shown as plain text. The target is never fetched, previewed or unfurled.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { safeHttpUrl } from './safeLink'

const props = defineProps<{ href: string | null | undefined }>()

const { t } = useI18n()
const url = computed(() => safeHttpUrl(props.href))
</script>

<template>
  <a
    v-if="url"
    :href="url.href"
    target="_blank"
    rel="noopener noreferrer"
    class="inline-flex max-w-full items-center gap-1 text-primary underline underline-offset-2 outline-none focus-visible:outline-2 focus-visible:outline-primary"
  >
    <span class="truncate">{{ url.host }}</span>
    <svg viewBox="0 0 24 24" class="h-4 w-4 shrink-0" aria-hidden="true">
      <path fill="currentColor" d="M19 19H5V5h7V3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z" />
    </svg>
    <span class="sr-only">{{ t('evidence.link.newTab') }}</span>
  </a>
  <span v-else data-testid="plain-link" class="break-all">{{ href }}</span>
</template>
