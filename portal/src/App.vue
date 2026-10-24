<script setup lang="ts">
import { computed, inject, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import logo from './assets/logo.png'
import { SUPPORTED_LOCALES, type Locale } from './i18n'
import { SESSION_KEY } from './session'
import AppealFormView from './views/AppealFormView.vue'
import CaseView from './views/CaseView.vue'
import ConversationView from './views/ConversationView.vue'
import ExpiredView from './views/ExpiredView.vue'
import LoginView from './views/LoginView.vue'

const { t, locale } = useI18n()
const session = inject(SESSION_KEY)!
const state = session.state

type View = 'case' | 'appeal' | 'conversation'
const view = ref<View>('case')

// Leaving the session (logout or expiry) always lands on the case page after the next sign-in.
watch(
  () => state.phase,
  (phase) => {
    if (phase !== 'in') view.value = 'case'
  },
)
watch(
  locale,
  (value) => {
    document.documentElement.lang = value
  },
  { immediate: true },
)

const languages = computed(() => SUPPORTED_LOCALES.map((code) => ({ code, label: t(`app.language.${code}`) })))
function pick(code: Locale) {
  locale.value = code
}
</script>

<template>
  <a
    href="#main"
    class="absolute left-4 top-2 z-10 -translate-y-20 rounded-md-sm bg-primary px-4 py-2 text-label-large text-on-primary focus:translate-y-0"
  >
    {{ t('app.skip') }}
  </a>

  <header class="olf-gradient px-4 py-6 sm:px-8">
    <div class="mx-auto flex max-w-3xl flex-wrap items-center justify-between gap-4">
      <div class="flex items-center gap-4 rounded-md-lg bg-[#1f1f23]/75 px-4 py-3 text-white">
        <img :src="logo" alt="" width="40" height="35" class="h-9 w-auto" />
        <div>
          <p class="text-title-medium">{{ t('app.name') }}</p>
          <p class="text-label-medium font-light tracking-widest">{{ t('app.tagline') }}</p>
        </div>
      </div>
      <nav :aria-label="t('app.tools')" class="flex items-center gap-2 rounded-md-lg bg-surface px-2 py-1">
        <button
          v-for="language in languages"
          :key="language.code"
          type="button"
          :lang="language.code"
          :aria-pressed="locale === language.code"
          class="md-state rounded-md-full px-3 py-1 text-label-large text-on-surface aria-pressed:bg-secondary-container aria-pressed:text-on-secondary-container"
          @click="pick(language.code)"
        >
          {{ language.label }}
        </button>
        <button
          v-if="state.phase === 'in'"
          type="button"
          class="md-state rounded-md-full px-3 py-1 text-label-large text-primary"
          @click="session.logout()"
        >
          {{ t('app.logout') }}
        </button>
      </nav>
    </div>
  </header>

  <main id="main" tabindex="-1" class="mx-auto max-w-3xl px-4 py-8 sm:px-8">
    <p v-if="state.phase === 'loading'" role="status" class="text-body-large text-on-surface-variant">{{ t('app.loading') }}</p>
    <LoginView v-else-if="state.phase === 'login'" />
    <ExpiredView v-else-if="state.phase === 'expired'" />
    <template v-else>
      <CaseView v-if="view === 'case'" @appeal="view = 'appeal'" @conversation="view = 'conversation'" />
      <AppealFormView v-else-if="view === 'appeal'" @back="view = 'case'" @filed="view = 'conversation'" />
      <ConversationView v-else @back="view = 'case'" />
    </template>
  </main>

  <footer class="mx-auto max-w-3xl px-4 pb-8 text-body-small text-on-surface-variant sm:px-8">
    <p>{{ t('app.footer') }}</p>
  </footer>
</template>
