import { defineNitroConfig } from 'nitropack/config'

// Production server for the admin UI. Vite builds the SPA to dist/ first (npm run build), then
// `nitro build` bundles this server into .output/. `npm run dev` does not use Nitro.
// Asset directories resolve against srcDir (server/), hence the ../dist.
export default defineNitroConfig({
  preset: 'node-server',
  srcDir: 'server',
  compatibilityDate: '2026-10-23',
  errorHandler: './server/error.ts',
  publicAssets: [
    // Content-hashed files never change; a miss is a real 404 (fallthrough is false for non-root bases).
    { dir: '../dist/assets', baseURL: '/assets', maxAge: 31536000, fallthrough: false },
    { dir: '../dist', baseURL: '/' },
  ],
  // index.html is bundled into the server so the SPA fallback needs no runtime file access.
  serverAssets: [{ baseName: 'ui', dir: '../dist', pattern: 'index.html' }],
})
