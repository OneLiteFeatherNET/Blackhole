<script setup lang="ts">
import { inject } from 'vue'
import { useI18n } from 'vue-i18n'
import { SESSION_KEY } from '../session'

const { t } = useI18n()
const session = inject(SESSION_KEY)!
const hasDraft = () => session.state.drafts.statement !== '' || session.state.drafts.message !== ''
</script>

<template>
  <section aria-labelledby="expired-title" class="rounded-md-lg bg-surface-container p-6">
    <h1 id="expired-title" class="text-headline-small text-on-surface">{{ t('expired.title') }}</h1>
    <p role="status" class="mt-2 text-body-large text-on-surface-variant">{{ t('expired.body') }}</p>
    <p v-if="hasDraft()" class="mt-2 rounded-md-sm bg-secondary-container px-4 py-3 text-body-medium text-on-secondary-container">
      {{ t('expired.draftKept') }}
    </p>
    <button
      type="button"
      class="md-state mt-6 inline-flex h-10 items-center justify-center rounded-md-full bg-primary px-6 text-label-large text-on-primary"
      @click="session.reauthenticate()"
    >
      {{ t('expired.signIn') }}
    </button>
  </section>
</template>
