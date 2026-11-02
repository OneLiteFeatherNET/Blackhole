<script setup lang="ts">
// The list of keyboard shortcuts (`?`, or the keyboard button of the top bar). The chords are those of
// web/src/nav/shortcuts.ts; the list is the place where they are documented for the viewer.
import { useI18n } from 'vue-i18n'
import MdButton from '../md/MdButton.vue'
import MdDialog from '../md/MdDialog.vue'

defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: [] }>()
const { t } = useI18n()

/** The keys as typed, and the translation key of the destination or action they reach. */
const rows: { keys: string[]; label: string }[] = [
  { keys: ['g', 'i'], label: t('nav.inbox') },
  { keys: ['g', 'c'], label: t('nav.cases') },
  { keys: ['g', 'a'], label: t('nav.appeals') },
  { keys: ['g', 'r'], label: t('nav.reports') },
  { keys: ['g', 'p'], label: t('nav.playerLookup') },
  { keys: ['?'], label: t('nav.shortcuts.help') },
]
</script>

<template>
  <MdDialog :open="open" :headline="t('nav.shortcuts.title')" @close="emit('close')">
    <p>{{ t('nav.shortcuts.intro') }}</p>
    <dl class="mt-4 grid grid-cols-[auto_1fr] items-center gap-x-6 gap-y-3" data-testid="shortcut-list">
      <template v-for="row in rows" :key="row.label">
        <dt class="flex gap-1">
          <template v-for="(key, index) in row.keys" :key="key">
            <kbd class="grid min-h-8 min-w-8 place-items-center rounded-md-xs border border-outline bg-surface-container px-2 font-mono text-label-large text-on-surface">{{ key }}</kbd>
            <span v-if="index < row.keys.length - 1" aria-hidden="true" class="text-label-medium">{{ t('nav.shortcuts.then') }}</span>
          </template>
        </dt>
        <dd class="text-body-large text-on-surface">{{ row.label }}</dd>
      </template>
    </dl>
    <template #actions>
      <MdButton variant="text" data-testid="shortcuts-close" @click="emit('close')">{{ t('nav.shortcuts.close') }}</MdButton>
    </template>
  </MdDialog>
</template>
