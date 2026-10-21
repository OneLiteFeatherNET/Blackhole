<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Punishment } from '../../api/types'
import { formatDateTime } from '../../format'
import MdCard from '../md/MdCard.vue'
import StatusPill from '../StatusPill.vue'
import { expiresAt, isAutomatic, reasonOf, stepLabel } from './punishmentInfo'

const props = defineProps<{ active: Record<string, Punishment> }>()

const { t, locale } = useI18n()
const entries = computed(() => Object.entries(props.active))
</script>

<template>
  <p v-if="entries.length === 0" class="text-body-large text-on-surface-variant">{{ t('player.noActive') }}</p>
  <ul v-else class="grid gap-4 md:grid-cols-2">
    <li v-for="[section, punishment] in entries" :key="section">
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
        <slot name="actions" :section="section" :punishment="punishment" />
      </MdCard>
    </li>
  </ul>
</template>
