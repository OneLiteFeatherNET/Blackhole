<script setup lang="ts">
// The interstitial between Blackhole and a link a player or staff member wrote in a chat. The target
// travels in the fragment, so it reaches no server log and no Referer. It is checked again here, and
// nothing redirects by itself: the reader decides to continue or to go back.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { decodeLeaveTarget, safeHttpUrl } from '../components/case/chat/safeUrl'
import MdButton from '../components/md/MdButton.vue'

const { t } = useI18n()
const route = useRoute()

const target = computed(() => {
  const decoded = decodeLeaveTarget(route.hash.replace(/^#/, ''))
  return decoded === null ? null : safeHttpUrl(decoded)
})

function back(): void {
  window.history.back()
}
</script>

<template>
  <section class="mx-auto flex max-w-2xl flex-col gap-5" data-testid="leave">
    <h2 class="text-headline-small text-on-surface">{{ t('leave.heading') }}</h2>
    <p class="text-body-large text-on-surface-variant">{{ t('leave.explain') }}</p>

    <template v-if="target">
      <p class="break-all rounded-md-md bg-surface-container-low p-4 text-body-large text-on-surface" data-testid="leave-target">{{ target }}</p>
    </template>
    <p v-else role="alert" class="rounded-md-md bg-error-container p-4 text-on-error-container" data-testid="leave-error">{{ t('leave.invalid') }}</p>

    <div class="flex flex-wrap gap-3">
      <MdButton variant="text" data-testid="leave-back" @click="back">{{ t('leave.back') }}</MdButton>
      <a
        v-if="target"
        :href="target"
        target="_blank"
        rel="noopener noreferrer nofollow ugc"
        class="inline-flex h-10 items-center rounded-md-full bg-primary px-6 text-label-large text-on-primary outline-none focus-visible:outline-2 focus-visible:outline-primary"
        data-testid="leave-continue"
      >{{ t('leave.continue') }}</a>
    </div>
  </section>
</template>
