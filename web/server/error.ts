import { defineNitroErrorHandler } from 'nitropack/runtime'
import { sendErrorResponse } from './lib/handlers'

export default defineNitroErrorHandler((error, event) => sendErrorResponse(error, event))
