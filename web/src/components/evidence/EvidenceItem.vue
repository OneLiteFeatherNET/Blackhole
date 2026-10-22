<script setup lang="ts">
import { computed, ref, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Evidence } from '../../api/types'
import { formatDateTime } from '../../format'
import MdButton from '../md/MdButton.vue'
import StatusPill from '../StatusPill.vue'
import AnticheatDetail from './AnticheatDetail.vue'
import ChatLogDetail from './ChatLogDetail.vue'
import { kindInfo } from './evidenceKind'
import FilePreview from './FilePreview.vue'
import { anticheatOf, chatLinesOf, descriptionOf } from './payload'
import SafeLink from './SafeLink.vue'

const props = defineProps<{ item: Evidence }>()
const emit = defineEmits<{ delete: [item: Evidence] }>()

const { t, locale } = useI18n()

const detailId = useId()
const expanded = ref(false)

const info = computed(() => kindInfo(props.item.kind))
const isLink = computed(() => props.item.kind === 'LINK' || props.item.kind === 'REPLAY')
/** Lifted and revoked items are kept for the record but no longer support a punishment. */
const inactive = computed(() => props.item.status !== 'VALID')

/** One line saying what the item is, without opening it. */
const summary = computed(() => {
  switch (props.item.kind) {
    case 'ANTICHEAT':
      return anticheatOf(props.item).check ?? ''
    case 'CHAT_LOG': {
      const n = chatLinesOf(props.item).length
      return t('evidence.item.lines', { n }, n)
    }
    case 'FILE':
      return props.item.file?.name ?? ''
    default:
      return descriptionOf(props.item) ?? ''
  }
})
</script>

<template>
  <li class="rounded-md-md border border-outline-variant p-4" :class="inactive ? 'bg-surface-container-low opacity-80' : 'bg-surface'" data-testid="evidence-item">
    <div class="flex flex-wrap items-start gap-3">
      <svg viewBox="0 0 24 24" class="mt-0.5 h-6 w-6 shrink-0 text-on-surface-variant" aria-hidden="true" data-testid="kind-icon" :data-kind="item.kind">
        <path :d="info.icon" fill="currentColor" />
      </svg>
      <div class="min-w-0 flex-1">
        <div class="flex flex-wrap items-center gap-2">
          <h4 class="text-title-small">{{ t(info.labelKey) }}</h4>
          <StatusPill v-if="inactive" :label="t(`evidence.status.${item.status}`)" tone="tertiary" data-testid="status" />
        </div>
        <p v-if="summary" class="break-words text-body-medium" data-testid="summary">{{ summary }}</p>
        <p v-if="isLink" class="text-body-medium"><SafeLink :href="item.reference" /></p>
        <p class="text-body-small text-on-surface-variant">
          {{ item.sourceSystem }} · {{ formatDateTime(item.capturedAt ?? item.createdAt, locale) }}
        </p>
      </div>
      <div class="flex shrink-0 gap-1">
        <MdButton
          v-if="!isLink"
          variant="text"
          data-testid="expand"
          :aria-expanded="expanded ? 'true' : 'false'"
          :aria-controls="detailId"
          @click="expanded = !expanded"
        >
          {{ expanded ? t('evidence.item.collapse') : t('evidence.item.expand') }}
        </MdButton>
        <MdButton variant="text" data-testid="delete" @click="emit('delete', item)">{{ t('evidence.item.delete') }}</MdButton>
      </div>
    </div>
    <div v-if="expanded && !isLink" :id="detailId" class="mt-3 border-t border-outline-variant pt-3" data-testid="detail">
      <AnticheatDetail v-if="item.kind === 'ANTICHEAT'" :item="item" />
      <ChatLogDetail v-else-if="item.kind === 'CHAT_LOG'" :item="item" />
      <FilePreview v-else-if="item.kind === 'FILE'" :item="item" />
    </div>
  </li>
</template>
