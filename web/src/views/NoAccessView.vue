<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute } from 'vue-router'
import { useSignOut } from '../auth/inject'
import MdButton from '../components/md/MdButton.vue'
import MdCard from '../components/md/MdCard.vue'
import { useSessionStore } from '../stores/session'

// Shown to a signed-in user whose roles open nothing. Nothing else is requested from the backend.
const { t } = useI18n()
const session = useSessionStore()
const signOut = useSignOut()
const route = useRoute()
// Reached through an admin-only page by someone who is signed in with another role.
const adminOnly = computed(() => route.query.reason === 'admin')
// Reached through an action on a player or a punishment by a viewer (the action routes are for moderators).
const moderatorOnly = computed(() => route.query.reason === 'moderator')
const heading = ref<HTMLElement | null>(null)

// A route change should move the focus to what is new, so a screen reader starts at the explanation.
onMounted(() => heading.value?.focus())
</script>

<template>
  <MdCard variant="filled" class="mx-auto max-w-xl">
    <h2 ref="heading" tabindex="-1" class="mb-2 text-headline-small outline-none">{{ adminOnly ? t('auth.noAccess.adminHeading') : moderatorOnly ? t('auth.noAccess.moderatorHeading') : t('auth.noAccess.heading') }}</h2>
    <p v-if="session.displayName" class="mb-2 text-body-medium text-on-surface-variant">
      {{ t('auth.signedInAs', { name: session.displayName }) }}
    </p>
    <p class="mb-4 text-body-large">{{ adminOnly ? t('auth.noAccess.adminBody') : moderatorOnly ? t('auth.noAccess.moderatorBody') : t('auth.noAccess.body') }}</p>
    <RouterLink
      v-if="adminOnly || moderatorOnly"
      to="/"
      data-testid="back-to-dashboard"
      class="mr-4 text-primary underline underline-offset-2 focus-ring"
    >
      {{ t('auth.noAccess.back') }}
    </RouterLink>
    <MdButton data-testid="sign-out" @click="signOut">{{ t('auth.signOut') }}</MdButton>
  </MdCard>
</template>
