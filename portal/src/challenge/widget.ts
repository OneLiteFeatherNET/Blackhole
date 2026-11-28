/**
 * The ALTCHA widget, loaded once at start-up (main.ts). It is the strict build (`altcha/external`): no bundled
 * workers, no blob or data URL, no inline style. The proof-of-work runs in one Web Worker, a same-origin chunk
 * that the portal's CSP allows through `worker-src 'self'`. Nothing here is imported by the views' tests, which
 * stand in for the element.
 */
import 'altcha/external'
import 'altcha/altcha.css'
import 'altcha/i18n/de'
import 'altcha/i18n/en'
import Pbkdf2Worker from 'altcha/workers/pbkdf2?worker'

// The one algorithm the backend issues (PBKDF2/SHA-256). Registered explicitly: the external build has no default.
$altcha.algorithms.set('PBKDF2/SHA-256', () => new Pbkdf2Worker())
