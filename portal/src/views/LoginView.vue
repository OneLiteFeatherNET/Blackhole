<script setup lang="ts">
import { inject, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { SESSION_KEY } from '../session'

const { t } = useI18n()
const session = inject(SESSION_KEY)!
const state = session.state
const banId = ref('')

async function submit() {
  const typed = banId.value
  // The Ban ID is a credential: it leaves the field as soon as it is sent and is never kept elsewhere.
  banId.value = ''
  await session.login(typed)
}
</script>

<template>
  <section aria-labelledby="login-title" class="rounded-md-lg bg-surface-container p-6">
    <h1 id="login-title" class="text-headline-small text-on-surface">{{ t('login.title') }}</h1>
    <p class="mt-2 text-body-large text-on-surface-variant">{{ t('login.intro') }}</p>

    <form class="mt-6 flex flex-col gap-4" novalidate @submit.prevent="submit">
      <div class="flex flex-col gap-1">
        <label for="ban-id" class="text-label-large text-on-surface">{{ t('login.label') }}</label>
        <input
          id="ban-id"
          v-model="banId"
          type="text"
          name="ban-id"
          autocomplete="off"
          autocapitalize="characters"
          spellcheck="false"
          inputmode="text"
          maxlength="32"
          required
          :aria-invalid="state.loginError === 'INVALID_BAN_ID'"
          aria-describedby="ban-id-hint ban-id-error"
          class="h-14 rounded-md-xs border border-outline bg-surface px-4 text-body-large uppercase tracking-widest text-on-surface focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
        />
        <p id="ban-id-hint" class="text-body-small text-on-surface-variant">{{ t('login.hint') }}</p>
      </div>

      <div id="ban-id-error" role="alert" aria-live="assertive">
        <p v-if="state.loginError === 'INVALID_BAN_ID'" class="rounded-md-sm bg-error-container px-4 py-3 text-body-medium text-on-error-container">
          {{ t('login.invalid') }}
        </p>
        <p v-else-if="state.loginError === 'UNAVAILABLE'" class="rounded-md-sm bg-error-container px-4 py-3 text-body-medium text-on-error-container">
          {{ t('login.unavailable') }}
        </p>
      </div>

      <div>
        <button
          type="submit"
          :disabled="state.busy"
          class="md-state inline-flex h-10 items-center justify-center rounded-md-full bg-primary px-6 text-label-large text-on-primary disabled:opacity-60"
        >
          {{ state.busy ? t('login.submitting') : t('login.submit') }}
        </button>
      </div>
    </form>

    <p class="mt-6 text-body-small text-on-surface-variant">{{ t('login.privacy') }}</p>
  </section>
</template>
