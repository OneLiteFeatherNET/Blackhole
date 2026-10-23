<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { useAuth } from '../auth/inject'
import MdButton from '../components/md/MdButton.vue'
import MdCard from '../components/md/MdCard.vue'

// Sign-in itself failed: the authority is unreachable, the callback was rejected or /auth/me failed.
const { t } = useI18n()
const route = useRoute()
const auth = useAuth()
const heading = ref<HTMLElement | null>(null)

const reason = computed(() => {
  const value = route.query.reason
  return value === 'callback' || value === 'signin' || value === 'me' ? value : 'signin'
})

onMounted(() => heading.value?.focus())

function retry(): void {
  void auth.signIn('/').catch(() => undefined)
}
</script>

<template>
  <MdCard variant="filled" class="mx-auto max-w-xl" role="alert">
    <h2 ref="heading" tabindex="-1" class="mb-2 text-headline-small outline-none">{{ t('auth.error.heading') }}</h2>
    <p class="mb-4 text-body-large">{{ t(`auth.error.${reason}`) }}</p>
    <MdButton data-testid="retry-sign-in" @click="retry">{{ t('auth.error.retry') }}</MdButton>
  </MdCard>
</template>
