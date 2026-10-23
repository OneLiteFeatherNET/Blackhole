<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import type { CaseRelated } from '../../api/types'
import { formatDateTime } from '../../format'
import StatusPill from '../StatusPill.vue'

defineProps<{ related: CaseRelated[] }>()

const { t, te, locale } = useI18n()
const label = (prefix: string, value: string) => (te(`${prefix}.${value}`) ? t(`${prefix}.${value}`) : value)
</script>

<template>
  <section :aria-label="t('case.related.title')" class="flex flex-col gap-2" data-testid="related">
    <h3 class="text-title-medium">{{ t('case.related.title') }}</h3>
    <p v-if="related.length === 0" class="text-body-medium text-on-surface-variant">{{ t('case.related.none') }}</p>
    <ul v-else class="flex flex-col gap-2">
      <li v-for="other in related" :key="other.identifier" class="flex flex-wrap items-center gap-2">
        <RouterLink
          :to="{ name: 'case', params: { reference: other.identifier } }"
          class="text-primary underline underline-offset-2 outline-none focus-visible:outline-2 focus-visible:outline-primary"
        >
          {{ other.shortId }}
        </RouterLink>
        <span v-if="other.section" class="text-body-medium">{{ other.section }}</span>
        <StatusPill :label="label('case.state', other.punishmentState)" />
        <StatusPill :label="label('case.status', other.caseStatus)" tone="primary" />
        <time :datetime="new Date(other.createdAt).toISOString()" class="text-body-small text-on-surface-variant">{{ formatDateTime(other.createdAt, locale) }}</time>
      </li>
    </ul>
  </section>
</template>
