import { defineNitroPlugin } from 'nitropack/runtime'
import { loadPortalConfigOrExit } from '../lib/startup'

// Runs before the server listens: invalid settings stop the process with a non-zero exit code.
export default defineNitroPlugin((nitroApp) => {
  const config = loadPortalConfigOrExit(process.env, {
    exit: (code) => process.exit(code),
    error: (message) => console.error(message),
  })
  nitroApp.hooks.hook('request', (event) => {
    event.context.portalConfig = config
  })
})
