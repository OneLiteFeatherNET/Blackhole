import { createApp } from 'vue'
import App from './App.vue'
import { createPortalApi } from './api/portal'
import { createPortalI18n } from './i18n'
import { createCsrfHolder, createPortalSession, SESSION_KEY } from './session'
import LeaveView from './views/LeaveView.vue'
import '@fontsource/roboto/latin-300.css'
import '@fontsource/roboto/latin-400.css'
import '@fontsource/roboto/latin-500.css'
import '@fontsource/roboto/latin-ext-400.css'
import '@fontsource/roboto/latin-ext-500.css'
import './styles/main.css'

// The interstitial needs no session: it only shows where a link goes (`/leave#<target>`).
if (window.location.pathname === '/leave') {
  createApp(LeaveView).use(createPortalI18n()).mount('#app')
} else {
  const csrf = createCsrfHolder()
  const api = createPortalApi({ fetch: (input, init) => fetch(input, init), csrf: () => csrf.value })
  const session = createPortalSession(api, csrf)

  createApp(App).provide(SESSION_KEY, session).use(createPortalI18n()).mount('#app')
  void session.bootstrap()
}
