import { eventHandler, send, setResponseHeaders, setResponseStatus } from 'h3'
import { portalConfigOf } from '../../lib/handlers'
import { securityTxtResponse } from '../../lib/security-txt'

// Public and anonymous: no login, no backend call, and not under /api, so the BFF allow-list does not apply.
export default eventHandler((event) => {
  const response = securityTxtResponse(portalConfigOf(event).securityTxt)
  setResponseStatus(event, response.status)
  setResponseHeaders(event, response.headers)
  return send(event, response.body)
})
