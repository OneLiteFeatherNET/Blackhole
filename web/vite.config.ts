import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { defineConfig, type Plugin } from 'vitest/config'

// Where the dev server forwards /api/ to; the Nitro server (server/api/) does the same in production.
const backend = process.env.BLACKHOLE_BACKEND_URL ?? 'http://localhost:8080'

// Dev only: /config.json comes from the git-ignored public/config.local.json (npm run dev:config) when it
// exists, otherwise from the placeholder fixture public/config.json.
function localConfig(): Plugin {
  const file = join(import.meta.dirname, 'public/config.local.json')
  return {
    name: 'blackhole-local-config',
    configureServer(server) {
      server.middlewares.use('/config.json', (_request, response, next) => {
        if (!existsSync(file)) return next()
        response.setHeader('Content-Type', 'application/json')
        response.setHeader('Cache-Control', 'no-store')
        response.end(readFileSync(file))
      })
    },
  }
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue(), tailwindcss(), localConfig()],
  server: {
    proxy: {
      '/api': { target: backend, changeOrigin: true, rewrite: (path) => path.replace(/^\/api/, '') },
    },
  },
  // public/config.json is the dev-server fixture only; the production server serves its own /config.json.
  build: { target: 'es2022', copyPublicDir: false },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.ts', 'server/**/*.test.ts', 'tests/**/*.test.ts'],
  },
})
