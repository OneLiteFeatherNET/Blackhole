<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useSignOut } from '../auth/inject'
import MdButton from '../components/md/MdButton.vue'
import MdCard from '../components/md/MdCard.vue'
import { useSessionStore } from '../stores/session'

// Shown to a signed-in user whose roles open nothing. Nothing else is requested from the backend.
const { t } = useI18n()
const session = useSessionStore()
const signOut = useSignOut()
const heading = ref<HTMLElement | null>(null)

// A route change should move the focus to what is new, so a screen reader starts at the explanation.
onMounted(() => heading.value?.focus())
</script>

<template>
  <MdCard variant="filled" class="mx-auto max-w-xl">
    <h2 ref="heading" tabindex="-1" class="mb-2 text-headline-small outline-none">{{ t('auth.noAccess.heading') }}</h2>
    <p v-if="session.displayName" class="mb-2 text-body-medium text-on-surface-variant">
      {{ t('auth.signedInAs', { name: session.displayName }) }}
    </p>
    <p class="mb-4 text-body-large">{{ t('auth.noAccess.body') }}</p>
    <MdButton data-testid="sign-out" @click="signOut">{{ t('auth.signOut') }}</MdButton>
  </MdCard>
</template>
