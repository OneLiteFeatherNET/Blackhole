<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import MdCard from '../components/md/MdCard.vue'

// Any path that is not a page of the app. Reached after sign-in like every other page.
const { t } = useI18n()
const heading = ref<HTMLElement | null>(null)

// A route change should move the focus to what is new, so a screen reader starts at the explanation.
onMounted(() => heading.value?.focus())
</script>

<template>
  <MdCard variant="filled" class="mx-auto max-w-xl">
    <h2 ref="heading" tabindex="-1" class="mb-2 text-headline-small outline-none">{{ t('notFound.heading') }}</h2>
    <p class="mb-4 text-body-large">{{ t('notFound.body') }}</p>
    <RouterLink
      to="/"
      data-testid="back-to-dashboard"
      class="text-primary underline underline-offset-2 outline-none focus-visible:outline-2 focus-visible:outline-primary"
    >
      {{ t('notFound.back') }}
    </RouterLink>
  </MdCard>
</template>
