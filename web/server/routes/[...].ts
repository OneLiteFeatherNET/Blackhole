import { useStorage } from 'nitropack/runtime'
import { createSpaFallbackHandler } from '../lib/handlers'

// index.html is bundled into the server from dist/ (nitro.config.ts serverAssets).
export default createSpaFallbackHandler(() => useStorage('assets:ui').getItem<string>('index.html'))
