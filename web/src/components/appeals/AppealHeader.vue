<script setup lang="ts">
// The header of an appeal page: type, short id with copy link, state, and the one primary action. It is a local
// section until the shared EntityHeader (restructure-staff-panel phase 4) replaces it; the parts keep its order.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Appeal } from '../../api/types'
import CopyLink from '../CopyLink.vue'
import MdButton from '../md/MdButton.vue'
import StatusPill from '../StatusPill.vue'
import { DECIDABLE_STATUSES } from './appealContext'

const props = defineProps<{ appeal: Appeal; canDecide: boolean }>()
const emit = defineEmits<{ decide: [] }>()

const { t } = useI18n()

const pending = computed(() => (DECIDABLE_STATUSES as readonly string[]).includes(props.appeal.status) || props.appeal.status === 'SUBMITTED')
const shortId = computed(() => props.appeal.identifier.slice(0, 8))
</script>

<template>
  <header class="flex flex-col gap-3" data-testid="appeal-header">
    <div class="flex flex-wrap items-center gap-3">
      <span class="rounded-md-sm bg-secondary-container px-2 py-1 text-label-large text-on-secondary-container" data-testid="type-label">
        {{ t('appeal.type') }}
      </span>
      <span class="font-mono text-title-medium" data-testid="short-id" :title="props.appeal.identifier">#{{ shortId }}</span>
      <StatusPill :label="t(`appeals.status.${props.appeal.status}`)" :tone="pending ? 'tertiary' : 'neutral'" data-testid="state" />
      <CopyLink />
    </div>
    <div class="flex flex-wrap items-center gap-3">
      <MdButton v-if="canDecide" data-testid="decide" @click="emit('decide')">{{ t('appeals.decide.open') }}</MdButton>
      <p v-else-if="!pending" class="text-body-large text-on-surface-variant" data-testid="already-decided">{{ t('appeal.alreadyDecided') }}</p>
    </div>
  </header>
</template>
