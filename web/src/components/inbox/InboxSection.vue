<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import type { ApiError } from '../../api/types'
import ResourceState from '../ResourceState.vue'

// One section of the work queue: its heading, its full count, the rows (the slot) and a "Show all" link to the
// filtered list. A section reads its own data, so a failed read shows its own error and blanks no other section.
// `note` replaces the rows when the section cannot be read for this caller at all (no staff identity).
const props = defineProps<{
  queue: string
  title: string
  count: number | null
  truncated?: boolean
  showAllTo: string | null
  loading: boolean
  error: ApiError | null
  emptyText: string
  note?: string | null
}>()
const emit = defineEmits<{ retry: [] }>()
const { t } = useI18n()
</script>

<template>
  <section
    :data-queue="props.queue"
    :data-testid="`queue-${props.queue}`"
    :aria-labelledby="`queue-${props.queue}-heading`"
    class="flex flex-col gap-3"
  >
    <div class="flex flex-wrap items-baseline justify-between gap-2">
      <h2 :id="`queue-${props.queue}-heading`" class="text-title-medium">{{ props.title }}</h2>
      <span v-if="props.count !== null" data-testid="section-count" class="text-headline-small">
        {{ props.count }}{{ props.truncated ? '+' : '' }}
      </span>
    </div>

    <p v-if="props.note" class="text-body-large text-on-surface-variant">{{ props.note }}</p>
    <ResourceState
      v-else
      :loading="props.loading"
      :error="props.error"
      :has-data="props.count !== null"
      @retry="emit('retry')"
    >
      <p v-if="props.count === 0" class="text-body-large text-on-surface-variant">{{ props.emptyText }}</p>
      <ul v-else class="flex flex-col divide-y">
        <slot />
      </ul>
    </ResourceState>

    <RouterLink
      v-if="props.showAllTo"
      data-testid="show-all"
      :to="props.showAllTo"
      class="inline-flex min-h-12 items-center self-start text-label-large text-primary underline underline-offset-2 outline-none focus-visible:outline-2 focus-visible:outline-primary"
    >
      {{ t('inbox.showAll') }}
    </RouterLink>
  </section>
</template>
