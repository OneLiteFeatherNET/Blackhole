<script setup lang="ts">
import { computed, inject } from 'vue'
import { useI18n } from 'vue-i18n'
import { formatDate, formatDateTime } from '../format'
import { SESSION_KEY } from '../session'

defineEmits<{ appeal: []; conversation: [] }>()

const { t, te, locale } = useI18n()
const session = inject(SESSION_KEY)!
const state = session.state

const facts = computed(() => state.punishment)
const appealState = computed(() => facts.value?.appeal.state)
const limited = computed(() => state.scope === 'LIMITED')
const canRead = computed(() => !limited.value && ['OPEN', 'DECIDED', 'COOLDOWN', 'FROZEN'].includes(appealState.value ?? ''))
const availableFrom = computed(() => facts.value?.appeal.availableFrom ?? null)

function stateLabel(value: string) {
  return te(`case.state.${value}`) ? t(`case.state.${value}`) : value
}
</script>

<template>
  <div class="flex flex-col gap-6">
    <section v-if="facts" aria-labelledby="facts-title" class="rounded-md-lg bg-surface-container p-6">
      <h1 id="facts-title" class="text-headline-small text-on-surface">{{ t('case.title') }}</h1>
      <dl class="mt-4 grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-[max-content_1fr]">
        <dt class="text-label-large text-on-surface-variant">{{ t('case.reference') }}</dt>
        <dd class="text-body-large text-on-surface">{{ facts.reference }}</dd>
        <dt class="text-label-large text-on-surface-variant">{{ t('case.type') }}</dt>
        <dd class="text-body-large text-on-surface">{{ facts.type }}</dd>
        <dt class="text-label-large text-on-surface-variant">{{ t('case.reason') }}</dt>
        <dd class="plain-text text-body-large text-on-surface">{{ facts.reason }}</dd>
        <dt class="text-label-large text-on-surface-variant">{{ t('case.section') }}</dt>
        <dd class="text-body-large text-on-surface">{{ facts.section }}</dd>
        <dt class="text-label-large text-on-surface-variant">{{ t('case.since') }}</dt>
        <dd class="text-body-large text-on-surface"><time :datetime="facts.createdAt">{{ formatDateTime(facts.createdAt, locale) }}</time></dd>
        <dt class="text-label-large text-on-surface-variant">{{ t('case.until') }}</dt>
        <dd class="text-body-large text-on-surface">
          <template v-if="facts.permanent">{{ t('case.permanent') }}</template>
          <time v-else-if="facts.expiresAt" :datetime="facts.expiresAt">{{ formatDateTime(facts.expiresAt, locale) }}</time>
        </dd>
        <dt class="text-label-large text-on-surface-variant">{{ t('case.status') }}</dt>
        <dd class="text-body-large text-on-surface">{{ stateLabel(facts.state) }}</dd>
        <dt class="text-label-large text-on-surface-variant">{{ t('case.evidence') }}</dt>
        <dd class="text-body-large text-on-surface">{{ t('case.evidenceCount', { count: facts.evidenceCount }) }}</dd>
      </dl>
    </section>
    <p v-else role="status" class="text-body-large text-on-surface-variant">{{ t('app.loading') }}</p>

    <section v-if="facts" aria-labelledby="appeal-title" class="rounded-md-lg bg-surface-container-low p-6">
      <h2 id="appeal-title" class="text-title-large text-on-surface">{{ t('appeal.title') }}</h2>

      <p v-if="limited" class="mt-3 rounded-md-sm bg-tertiary-container px-4 py-3 text-body-medium text-on-tertiary-container">
        {{ t('notice.limited') }}
      </p>
      <template v-else>
        <p v-if="appealState === 'FORFEITED'" class="mt-3 rounded-md-sm bg-tertiary-container px-4 py-3 text-body-medium text-on-tertiary-container">
          {{ t('notice.forfeited') }}
        </p>
        <p v-else-if="appealState === 'FROZEN'" class="mt-3 rounded-md-sm bg-tertiary-container px-4 py-3 text-body-medium text-on-tertiary-container">
          {{ t('notice.frozen') }}
        </p>
        <p v-else-if="appealState === 'NOT_YET'" class="mt-3 text-body-large text-on-surface-variant">
          <template v-if="availableFrom">{{ t('appeal.notYet', { date: formatDate(availableFrom, locale) }) }}</template>
          <template v-else>{{ t('appeal.notYetUnknown') }}</template>
        </p>
        <p v-else-if="appealState === 'AVAILABLE'" class="mt-3 text-body-large text-on-surface-variant">{{ t('appeal.available') }}</p>
        <p v-else-if="appealState === 'OPEN'" class="mt-3 text-body-large text-on-surface-variant">{{ t('appeal.open') }}</p>
        <p v-else-if="appealState === 'DECIDED'" class="mt-3 text-body-large text-on-surface-variant">{{ t('appeal.decided') }}</p>
        <p v-else-if="appealState === 'COOLDOWN'" class="mt-3 text-body-large text-on-surface-variant">
          <template v-if="availableFrom">{{ t('appeal.cooldown', { date: formatDate(availableFrom, locale) }) }}</template>
          <template v-else>{{ t('appeal.cooldownUnknown') }}</template>
        </p>
      </template>

      <div class="mt-4 flex flex-wrap gap-3">
        <button
          v-if="!limited && appealState === 'AVAILABLE'"
          type="button"
          class="md-state inline-flex h-10 items-center justify-center rounded-md-full bg-primary px-6 text-label-large text-on-primary"
          @click="$emit('appeal')"
        >
          {{ t('appeal.file') }}
        </button>
        <button
          v-if="canRead"
          type="button"
          class="md-state inline-flex h-10 items-center justify-center rounded-md-full border border-outline px-6 text-label-large text-primary"
          @click="$emit('conversation')"
        >
          {{ t('appeal.openConversation') }}
        </button>
      </div>
    </section>
  </div>
</template>
