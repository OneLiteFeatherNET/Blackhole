<script setup lang="ts">
// A read-only chat transcript. Speakers arrive as owner hashes or as aliases the sender chose; hashes
// are resolved to a name only through the in-memory lookup of this session and never stored.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Evidence } from '../../api/types'
import { formatDateTime } from '../../format'
import { useLookupStore } from '../../stores/lookup'
import { chatLinesOf } from './payload'

const props = defineProps<{ item: Evidence }>()

const OWNER_HASH = /^[0-9a-f]{128}$/i

const { t, locale } = useI18n()
const lookup = useLookupStore()

const lines = computed(() => chatLinesOf(props.item))

/** Names for the speakers; an unresolved hash becomes "Player N" in order of first appearance. */
const speakerLabels = computed(() => {
  const labels = new Map<string, string>()
  let unknown = 0
  for (const { speaker } of lines.value) {
    if (speaker === null || labels.has(speaker)) continue
    const name = lookup.nameOf(speaker)
    if (name) labels.set(speaker, name)
    else if (OWNER_HASH.test(speaker)) labels.set(speaker, t('evidence.chat.player', { n: ++unknown }))
    else labels.set(speaker, speaker)
  }
  return labels
})

function speakerOf(speaker: string | null): string {
  return speaker === null ? t('evidence.chat.system') : (speakerLabels.value.get(speaker) ?? speaker)
}

function timeOf(timestamp: number | string | null): string {
  if (timestamp === null) return ''
  if (typeof timestamp === 'number') return formatDateTime(timestamp, locale.value)
  const parsed = Date.parse(timestamp)
  return Number.isNaN(parsed) ? timestamp : formatDateTime(parsed, locale.value)
}
</script>

<template>
  <ol class="flex flex-col gap-1 text-body-medium" :aria-label="t('evidence.chat.label')">
    <li v-for="(line, index) in lines" :key="index" class="flex flex-wrap gap-x-2" data-testid="chat-line">
      <span class="text-body-small text-on-surface-variant">{{ timeOf(line.timestamp) }}</span>
      <span class="font-medium" data-testid="speaker">{{ speakerOf(line.speaker) }}</span>
      <span class="break-words">{{ line.text }}</span>
    </li>
  </ol>
</template>
