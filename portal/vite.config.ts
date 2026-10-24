import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig } from 'vitest/config'

// Dev only: the BFF (the Nitro server, `node .output/server/index.mjs`) answers /api; the dev server forwards to it.
const bff = process.env.BLACKHOLE_PORTAL_DEV_BFF ?? 'http://localhost:3000'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue(), tailwindcss()],
  server: { proxy: { '/api': { target: bff, changeOrigin: false } } },
  build: { target: 'es2022', copyPublicDir: false },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.ts', 'server/**/*.test.ts', 'tests/**/*.test.ts'],
    setupFiles: ['src/test/warmup.ts'],
    // Cold-start work (transforming App.vue and its views, jsdom, first mount) is moved into a beforeAll in
    // the warm-up setup, but CI runners (Windows, macOS, Ubuntu) can still be slow under load: the first view
    // test took 5-6.5 s against the 5000 ms default. 15 s leaves headroom without hiding a real hang.
    testTimeout: 15_000,
    hookTimeout: 60_000,
  },
})
