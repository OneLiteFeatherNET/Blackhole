<script setup lang="ts">
// The trail of a detail page: every item but the last is a link to the list it came from; the last one is
// the current page and is not a link. The parent is named by the route, never guessed from the path.
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'

export interface Crumb {
  label: string
  to?: string
}

defineProps<{ items: Crumb[] }>()

const { t } = useI18n()
</script>

<template>
  <nav :aria-label="t('nav.breadcrumbs')">
    <ol class="flex flex-wrap items-center gap-1 text-body-medium text-on-surface-variant">
      <li v-for="(item, index) in items" :key="`${index}-${item.label}`" class="flex items-center gap-1">
        <span v-if="index > 0" aria-hidden="true">›</span>
        <RouterLink
          v-if="index < items.length - 1 && item.to"
          :to="item.to"
          class="text-primary underline underline-offset-2 outline-none focus-visible:outline-2 focus-visible:outline-primary"
        >
          {{ item.label }}
        </RouterLink>
        <span v-else :aria-current="index === items.length - 1 ? 'page' : undefined" class="text-on-surface">{{ item.label }}</span>
      </li>
    </ol>
  </nav>
</template>
