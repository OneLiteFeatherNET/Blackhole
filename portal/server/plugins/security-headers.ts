import { defineNitroPlugin } from 'nitropack/runtime'
import { applySecurityHeaders } from '../lib/handlers'

export default defineNitroPlugin((nitroApp) => {
  nitroApp.hooks.hook('request', applySecurityHeaders)
})
