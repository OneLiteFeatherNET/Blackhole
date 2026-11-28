<script setup lang="ts">
// The bar that appears while cases are selected. It sits under the top app bar (h-16) and holds the count, the cap
// note and the clear control; the bulk actions plug into the `actions` slot. The live region is always rendered, so
// the count is announced when it changes, including when it drops to zero. The root is `display: contents`, so the
// sticky bar sticks against the page's own column rather than against this wrapper.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import MdButton from '../md/MdButton.vue'

const props = defineProps<{ count: number; atCap: boolean; cap: number }>()
const emit = defineEmits<{ clear: [] }>()

const { t } = useI18n()
const summary = computed(() => t('case.selection.count', { count: props.count }))
</script>

<template>
  <div class="contents">
    <p role="status" aria-live="polite" class="sr-only">{{ summary }}</p>
    <div
      v-if="props.count > 0"
      role="region"
      :aria-label="t('case.selection.region')"
      data-testid="selection-bar"
      class="sticky top-16 z-20 flex flex-wrap items-center gap-3 rounded-md-md bg-secondary-container px-4 py-2 text-on-secondary-container"
    >
      <span class="text-title-small" data-testid="selection-count">{{ summary }}</span>
      <p v-if="props.atCap" class="text-body-medium" data-testid="selection-limit">{{ t('case.selection.limit', { cap: props.cap }) }}</p>
      <div class="ml-auto flex flex-wrap items-center gap-2">
        <slot name="actions" />
        <MdButton variant="text" data-testid="selection-clear" @click="emit('clear')">{{ t('case.selection.clear') }}</MdButton>
      </div>
    </div>
  </div>
</template>
