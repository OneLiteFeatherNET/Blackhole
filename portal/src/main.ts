import { createApp } from 'vue'
import App from './App.vue'
import { createPortalApi } from './api/portal'
import { createPortalI18n } from './i18n'
import { createCsrfHolder, createPortalSession, SESSION_KEY } from './session'
import '@fontsource/roboto/latin-300.css'
import '@fontsource/roboto/latin-400.css'
import '@fontsource/roboto/latin-500.css'
import '@fontsource/roboto/latin-ext-400.css'
import '@fontsource/roboto/latin-ext-500.css'
import './styles/main.css'

const csrf = createCsrfHolder()
const api = createPortalApi({ fetch: (input, init) => fetch(input, init), csrf: () => csrf.value })
const session = createPortalSession(api, csrf)

createApp(App).provide(SESSION_KEY, session).use(createPortalI18n()).mount('#app')
void session.bootstrap()
