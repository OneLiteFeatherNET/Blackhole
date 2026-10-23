<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import type { Punishment } from '../../api/types'
import { formatDateTime } from '../../format'
import EvidenceDialog from '../evidence/EvidenceDialog.vue'
import MdButton from '../md/MdButton.vue'
import MdCard from '../md/MdCard.vue'
import StatusPill from '../StatusPill.vue'
import { expiresAt, isAutomatic, reasonOf, stepLabel } from './punishmentInfo'

const props = defineProps<{ active: Record<string, Punishment> }>()

const { t, locale } = useI18n()
const entries = computed(() => Object.entries(props.active))
const evidenceFor = ref<string | null>(null)
</script>

<template>
  <p v-if="entries.length === 0" class="text-body-large text-on-surface-variant">{{ t('player.noActive') }}</p>
  <ul v-else class="grid gap-4 md:grid-cols-2">
    <li v-for="([section, punishment], index) in entries" :key="section" class="md-enter" :style="{ '--md-i': index }">
      <MdCard variant="filled" class="h-full" :data-testid="`active-${section}`">
        <div class="mb-2 flex flex-wrap items-center gap-2">
          <h3 class="text-title-medium">{{ section }}</h3>
          <StatusPill
            :label="punishment.permanent ? t('player.permanent') : t('player.temporary')"
            :tone="punishment.permanent ? 'error' : 'tertiary'"
          />
          <StatusPill v-if="isAutomatic(punishment)" :label="t('player.automatic')" />
        </div>
        <p class="text-body-large">{{ reasonOf(punishment) || t('player.noReason') }}</p>
        <dl class="mt-2 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-body-medium text-on-surface-variant">
          <template v-if="stepLabel(punishment)">
            <dt>{{ t('player.step') }}</dt>
            <dd>{{ stepLabel(punishment) }}</dd>
          </template>
          <template v-if="expiresAt(punishment) !== null">
            <dt>{{ t('player.until') }}</dt>
            <dd>{{ formatDateTime(expiresAt(punishment) as number, locale) }}</dd>
          </template>
          <template v-if="punishment.createdAt">
            <dt>{{ t('player.since') }}</dt>
            <dd>{{ formatDateTime(punishment.createdAt, locale) }}</dd>
          </template>
        </dl>
        <div class="mt-3">
          <MdButton variant="text" :data-testid="`open-evidence-${section}`" @click="evidenceFor = punishment.identifier">
            {{ t('evidence.open') }}
          </MdButton>
          <RouterLink
            :to="{ name: 'case', params: { reference: punishment.identifier } }"
            class="md-state inline-flex h-10 items-center rounded-md-full px-6 text-label-large text-primary"
            :data-testid="`open-case-${section}`"
          >
            {{ t('case.open') }}
          </RouterLink>
        </div>
        <slot name="actions" :section="section" :punishment="punishment" />
      </MdCard>
    </li>
  </ul>
  <EvidenceDialog v-if="evidenceFor" open :punishment="evidenceFor" @close="evidenceFor = null" />
</template>
