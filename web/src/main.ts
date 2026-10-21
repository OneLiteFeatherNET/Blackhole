import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { createBlackholeApi } from './api/blackhole'
import { loadConfig } from './api/config'
import { API_KEY } from './api/inject'
import { i18n } from './i18n'
import router from './router'
import { useUiStore } from './stores/ui'
import '@fontsource/roboto/latin-400.css'
import '@fontsource/roboto/latin-500.css'
import '@fontsource/roboto/latin-ext-400.css'
import '@fontsource/roboto/latin-ext-500.css'
import './styles/main.css'

/** The reverse proxy (nginx in the container, Vite in development) forwards this prefix to the backend. */
const API_BASE_URL = '/api'

const config = await loadConfig()

const pinia = createPinia()

createApp(App)
  .provide(
    API_KEY,
    createBlackholeApi({ baseUrl: API_BASE_URL, source: config.source, actingAs: () => useUiStore(pinia).actingAs }),
  )
  .use(pinia)
  .use(i18n)
  .use(router)
  .mount('#app')
