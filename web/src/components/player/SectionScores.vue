<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { EloSectionScore } from '../../api/types'
import MdCard from '../md/MdCard.vue'
import ScoreBar from '../ScoreBar.vue'
import StatusPill from '../StatusPill.vue'

defineProps<{ scores: EloSectionScore[] }>()

const { t } = useI18n()
</script>

<template>
  <ul class="grid gap-4 md:grid-cols-2">
    <li v-for="(entry, index) in scores" :key="entry.section" class="md-enter" :style="{ '--md-i': index }">
      <MdCard variant="outlined" class="h-full" :data-testid="`score-${entry.section}`">
        <div class="mb-3 flex items-start justify-between gap-2">
          <div class="min-w-0">
            <h3 class="truncate text-title-medium">{{ entry.displayName }}</h3>
            <StatusPill :label="t(`enforcement.${entry.enforcement}`)" />
          </div>
          <p class="text-headline-medium" :aria-label="t('player.scoreOf', { section: entry.displayName, score: entry.score })">
            {{ entry.score }}
          </p>
        </div>
        <ScoreBar
          :label="entry.displayName"
          :score="entry.score"
          :base-score="entry.baseScore"
          :soft-threshold="entry.softThreshold"
          :permanent-threshold="entry.permanentThreshold"
        />
        <slot name="actions" :entry="entry" />
      </MdCard>
    </li>
  </ul>
</template>
