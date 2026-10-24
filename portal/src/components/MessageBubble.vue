<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ChatAttachment, ConversationEntry } from '../api/contract'
import { attachmentUrl } from '../api/portal'
import { formatSize, isImage } from '../chat/files'
import { MarkdownTree } from '../chat/markdownTree'
import { formatDateTime } from '../format'

const props = defineProps<{
  entry: ConversationEntry
  /** Who the bubble is from, in words ("You", "Moderation team"). Staff are never named. */
  authorLabel: string
  /** The viewer's own side: the player's messages. */
  own: boolean
  /** The same author as the message before: the name is not repeated. */
  grouped: boolean
}>()

const emit = defineEmits<{ image: [src: string, name: string] }>()
const { t, locale } = useI18n()

interface FileView {
  attachment: ChatAttachment
  url: string
  image: boolean
}

/** Only attachments with a plain id are shown; anything else is left out rather than linked. */
const files = computed<FileView[]>(() => {
  if (props.entry.removed) return []
  const list: FileView[] = []
  for (const attachment of props.entry.attachments ?? []) {
    const url = attachmentUrl(attachment.attachmentId)
    if (url !== null) list.push({ attachment, url, image: isImage(attachment.contentType) })
  }
  return list
})

const share = computed(() => props.entry.share ?? null)
const shareImage = computed(() => {
  const current = share.value
  if (!current || current.state !== 'ACTIVE' || current.mode !== 'FILE') return null
  return attachmentUrl(current.shareId)
})

const shareStatus = computed(() => {
  const state = share.value?.state
  if (state === 'ACTIVE') return t('chat.share.title')
  if (state === 'WITHDRAWN') return t('chat.share.noLonger')
  return t('chat.share.unavailable')
})

function typeLabel(attachment: ChatAttachment): string {
  if (attachment.contentType === 'application/pdf') return t('chat.file.pdf')
  if (attachment.contentType === 'text/plain') return t('chat.file.text')
  return t('chat.file.image')
}

function timeOf(entry: ConversationEntry) {
  return formatDateTime(entry.occurredAt, locale.value)
}
</script>

<template>
  <div
    class="flex flex-col gap-2 rounded-md-lg px-4 py-3"
    :class="own ? 'bg-secondary-container text-on-secondary-container' : 'bg-surface-container-high text-on-surface'"
  >
    <p v-if="!grouped" class="text-label-large">{{ authorLabel }}</p>

    <p v-if="entry.removed" class="text-body-large italic">{{ t('conversation.removed') }}</p>
    <template v-else>
      <MarkdownTree v-if="entry.blocks" :nodes="entry.blocks" class="text-body-large" />
      <p v-else-if="entry.text !== null && entry.text !== undefined" class="plain-text text-body-large">{{ entry.text }}</p>
    </template>

    <ul v-if="files.length > 0" class="flex list-none flex-wrap gap-2 p-0" :aria-label="t('chat.files')">
      <li v-for="file in files" :key="file.attachment.attachmentId" class="max-w-full">
        <button
          v-if="file.image"
          type="button"
          class="md-state block overflow-hidden rounded-md-sm bg-surface"
          :aria-label="t('chat.lightbox.open', { name: file.attachment.name })"
          @click="emit('image', file.url, file.attachment.name)"
        >
          <img :src="file.url" :alt="file.attachment.name" loading="lazy" class="max-h-48 max-w-full object-contain" />
        </button>
        <div v-else class="flex max-w-full flex-col gap-1 rounded-md-sm bg-surface px-3 py-2 text-on-surface">
          <p class="text-label-large break-all">{{ file.attachment.name }}</p>
          <p class="text-body-small text-on-surface-variant">{{ typeLabel(file.attachment) }} · {{ formatSize(file.attachment.size) }}</p>
          <a
            :href="file.url"
            download
            class="text-label-large text-primary underline underline-offset-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
          >
            {{ t('chat.download', { name: file.attachment.name }) }}
          </a>
        </div>
      </li>
    </ul>

    <figure v-if="share" class="flex flex-col gap-2 rounded-md-sm border border-outline-variant bg-surface p-3 text-on-surface">
      <figcaption class="text-label-large">{{ shareStatus }}</figcaption>
      <template v-if="share.state === 'ACTIVE' && share.mode === 'FILE'">
        <button
          v-if="shareImage"
          type="button"
          class="md-state block overflow-hidden rounded-md-sm"
          :aria-label="t('chat.lightbox.open', { name: t('chat.share.image') })"
          @click="emit('image', shareImage, t('chat.share.image'))"
        >
          <img :src="shareImage" :alt="t('chat.share.image')" loading="lazy" class="max-h-48 max-w-full object-contain" />
        </button>
      </template>
      <blockquote v-else-if="share.state === 'ACTIVE' && share.lines" class="flex flex-col gap-1 border-l-2 border-primary pl-3">
        <p class="text-body-small text-on-surface-variant">{{ t('chat.share.excerpt') }}</p>
        <p v-for="(line, index) in share.lines" :key="index" class="text-body-medium">
          <strong class="text-label-large">{{ line.speaker }}:</strong>
          <span class="plain-text"> {{ line.text }}</span>
        </p>
      </blockquote>
    </figure>

    <p class="text-body-small opacity-80"><time :datetime="entry.occurredAt">{{ timeOf(entry) }}</time></p>
  </div>
</template>
