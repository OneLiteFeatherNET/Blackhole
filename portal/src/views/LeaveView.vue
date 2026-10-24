<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { isTrustedUrl, leaveTarget } from '../chat/links'

/**
 * The interstitial behind every external link: `/leave#<target>`. It shows where the link goes and
 * never redirects by itself; the visitor chooses to continue. The target is validated again here,
 * because anybody can hand over a `/leave#...` URL.
 */
const { t } = useI18n()

const target = computed(() => leaveTarget(window.location.hash))
const host = computed(() => (target.value ? new URL(target.value).hostname : ''))
const trusted = computed(() => target.value !== null && isTrustedUrl(target.value))

function back() {
  if (window.history.length > 1) window.history.back()
  else window.location.assign('/')
}
</script>

<template>
  <a
    href="#leave-main"
    class="absolute left-4 top-2 z-10 -translate-y-20 rounded-md-sm bg-primary px-4 py-2 text-label-large text-on-primary focus:translate-y-0"
  >
    {{ t('app.skip') }}
  </a>
  <main id="leave-main" tabindex="-1" class="mx-auto flex max-w-xl flex-col gap-6 px-4 py-8 sm:px-8">
    <h1 class="text-headline-small text-on-surface">{{ t('leave.title') }}</h1>

    <template v-if="target">
      <p class="text-body-large text-on-surface">{{ t('leave.explain') }}</p>
      <section class="flex flex-col gap-2 rounded-md-lg bg-surface-container p-6" :aria-label="t('leave.address')">
        <p class="text-label-large text-on-surface-variant">{{ t('leave.address') }}</p>
        <p class="break-all text-body-large text-on-surface" data-testid="leave-target">{{ target }}</p>
        <p class="text-body-medium text-on-surface-variant">{{ t('leave.host', { host }) }}</p>
        <p v-if="trusted" class="text-body-medium text-on-surface-variant">{{ t('leave.trusted') }}</p>
      </section>
      <p class="rounded-md-sm bg-tertiary-container px-4 py-3 text-body-medium text-on-tertiary-container">{{ t('leave.warning') }}</p>
      <div class="flex flex-wrap gap-3">
        <button
          type="button"
          class="md-state inline-flex h-10 items-center justify-center rounded-md-full border border-outline px-6 text-label-large text-primary"
          @click="back"
        >
          {{ t('leave.back') }}
        </button>
        <a
          :href="target"
          target="_blank"
          rel="noopener noreferrer nofollow ugc"
          class="md-state inline-flex h-10 items-center justify-center rounded-md-full bg-primary px-6 text-label-large text-on-primary"
        >
          {{ t('leave.continue') }}
        </a>
      </div>
    </template>
    <template v-else>
      <p role="alert" class="rounded-md-sm bg-error-container px-4 py-3 text-body-medium text-on-error-container">{{ t('leave.invalid') }}</p>
      <div>
        <button
          type="button"
          class="md-state inline-flex h-10 items-center justify-center rounded-md-full border border-outline px-6 text-label-large text-primary"
          @click="back"
        >
          {{ t('leave.back') }}
        </button>
      </div>
    </template>
  </main>
</template>
