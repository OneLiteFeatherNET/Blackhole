<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { ApiError } from '../api/types'
import MdProgress from './md/MdProgress.vue'

// Wraps the content of a screen: a progress bar while the first load runs, an alert with a retry
// button when it failed, the slot otherwise. A reload keeps the content and only shows the bar.
defineProps<{ loading: boolean; error: ApiError | null; hasData: boolean }>()
const emit = defineEmits<{ retry: [] }>()

const { t } = useI18n()
</script>

<template>
  <div>
    <MdProgress v-if="loading" :label="t('state.loading')" class="mb-4" />
    <div
      v-if="error"
      role="alert"
      class="mb-4 flex flex-wrap items-center gap-3 rounded-md-md bg-error-container p-4 text-on-error-container"
    >
      <div class="min-w-0 flex-1">
        <p class="text-title-small">{{ t(`state.error.${error.kind}`) }}</p>
        <p class="text-body-medium break-words">{{ error.message }}</p>
      </div>
      <button
        type="button"
        data-testid="retry"
        class="md-state h-10 rounded-md-full px-4 text-label-large text-on-error-container"
        @click="emit('retry')"
      >
        {{ t('state.retry') }}
      </button>
    </div>
    <slot v-if="hasData" />
  </div>
</template>
