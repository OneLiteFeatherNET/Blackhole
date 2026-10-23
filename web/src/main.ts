import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { createBlackholeApi } from './api/blackhole'
import { loadConfig } from './api/config'
import { API_KEY, EVIDENCE_LIMITS_KEY } from './api/inject'
import { createAuthService, createUserManager } from './auth/authService'
import { AUTH_KEY } from './auth/inject'
import { i18n } from './i18n'
import router from './router'
import { installAuthGuard } from './router/authGuard'
import { useSessionStore } from './stores/session'
import '@fontsource/roboto/latin-400.css'
import '@fontsource/roboto/latin-500.css'
import '@fontsource/roboto/latin-ext-400.css'
import '@fontsource/roboto/latin-ext-500.css'
import './styles/main.css'

/** The reverse proxy (the Nitro server in the container, Vite in development) forwards this prefix to the backend. */
const API_BASE_URL = '/api'

const config = await loadConfig()

if (!config.oidc) {
  // Nothing can be shown without a sign-in, so say why instead of leaving a blank page.
  const notice = document.getElementById('app')
  if (notice) {
    notice.setAttribute('role', 'alert')
    notice.textContent = i18n.global.t('auth.notConfigured')
  }
} else {
  const pinia = createPinia()
  const session = useSessionStore(pinia)
  const auth = createAuthService(createUserManager(config.oidc), () => router.currentRoute.value.fullPath)
  const api = createBlackholeApi({
    baseUrl: API_BASE_URL,
    auth: {
      getAccessToken: auth.getAccessToken,
      renew: auth.renew,
      signIn: () => void auth.signIn(router.currentRoute.value.fullPath).catch(() => undefined),
      forbidden: () => {
        session.forbidden = true
      },
    },
  })
  installAuthGuard(router, { auth, session, api })

  createApp(App)
    .provide(API_KEY, api)
    .provide(AUTH_KEY, auth)
    .provide(EVIDENCE_LIMITS_KEY, config.evidence)
    .use(pinia)
    .use(i18n)
    .use(router)
    .mount('#app')
}
