import { securityTxtHandler } from '../../lib/handlers'

// Public and anonymous: answered before the single-page fallback of routes/[...].ts, which is a catch-all.
export default securityTxtHandler
