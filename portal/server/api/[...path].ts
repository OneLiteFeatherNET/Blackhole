import { eventHandler } from 'h3'
import { createBffHandler } from '../lib/bff'
import { portalConfigOf } from '../lib/handlers'

// The config is validated at startup and put on the event by the config plugin.
export default eventHandler((event) => createBffHandler({ config: portalConfigOf(event), fetchImpl: fetch })(event))
