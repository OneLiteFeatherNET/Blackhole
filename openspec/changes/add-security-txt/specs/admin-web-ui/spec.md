# Spec Delta

## ADDED Requirements

### Requirement: Public disclosure file ahead of the single-page fallback
The panel server SHALL answer `GET /.well-known/security.txt` with the RFC 9116 document of the `security-disclosure` capability before the single-page fallback applies, so that the route never returns `index.html`. The route needs no login and makes no backend request.

#### Scenario: Disclosure file is not the SPA
- **WHEN** a browser requests `/.well-known/security.txt` on the panel host
- **THEN** the response is `text/plain; charset=utf-8` from the disclosure route, not `index.html` with status 200

#### Scenario: Other well-known paths keep the fallback rules
- **WHEN** a browser requests `/.well-known/unknown` on the panel host
- **THEN** the single-page fallback rules of this capability apply unchanged
