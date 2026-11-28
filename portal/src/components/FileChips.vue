<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { formatSize, type DraftFile } from '../chat/files'

/** The files of a draft as chips: name, size, state and the way out. Shared by the composer and the appeal form. */

defineProps<{ files: DraftFile[] }>()
const emit = defineEmits<{ remove: [key: string] }>()

const { t } = useI18n()

/** The codes a chip can show; anything else from the server reads as "could not be accepted". */
const CHIP_ERRORS = new Set([
  'UNSUPPORTED_TYPE',
  'TOO_LARGE',
  'FILE_FLAGGED',
  'FILE_ACTIVE_CONTENT',
  'INVALID_FILE',
  'QUOTA',
  'NO_APPEAL',
  'FORBIDDEN',
  'CONFLICT',
  'UNAVAILABLE',
])

function stateLabel(file: DraftFile): string {
  if (file.state === 'uploading') return t('chat.state.uploading')
  if (file.state === 'staged') return t('chat.state.staged')
  if (file.state === 'picked') return t('chat.state.picked')
  return file.code && CHIP_ERRORS.has(file.code) ? t(`chat.errors.${file.code}`) : t('chat.errors.INVALID_FILE')
}
</script>

<template>
  <ul v-if="files.length > 0" :aria-label="t('chat.attached')" class="flex list-none flex-wrap gap-2 p-0" data-testid="chips">
    <li v-for="file in files" :key="file.key" class="flex max-w-full items-center gap-2 rounded-md-sm bg-surface-container-high py-1 pl-1 pr-2 text-body-small text-on-surface">
      <img v-if="file.preview" :src="file.preview" :alt="file.name" class="h-10 w-10 rounded-md-xs object-cover" />
      <span class="break-all">{{ file.name }}</span>
      <span class="text-on-surface-variant">{{ formatSize(file.size) }}</span>
      <span :class="file.state === 'failed' ? 'text-error' : 'text-on-surface-variant'">{{ stateLabel(file) }}</span>
      <button
        type="button"
        class="md-state rounded-md-full px-2 text-label-large text-primary"
        :aria-label="t('chat.remove', { name: file.name })"
        @click="emit('remove', file.key)"
      >
        {{ t('chat.removeShort') }}
      </button>
    </li>
  </ul>
</template>
