# Specification Quality Checklist: Ban-Evasion Detection

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-07-19
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- Retroactive spec, derived directly from the existing implementation
  (`backend/.../evasion/` package: `IpCorrelationService`, `IpCorrelationRetentionSweeper`,
  `IpCorrelationTokenEntity`, `IpCorrelationTokenRepository`, `EvasionController`,
  `EvasionRecordDTO`, plus the calling site in the Velocity plugin's
  `PlayerLoginListener`) — no [NEEDS CLARIFICATION] markers were needed.
- Two revisions made during validation:
  1. The initial draft used vaguer phrasing ("network connection", "connection point")
     to avoid sounding implementation-specific. On review this made several requirements
     harder to test unambiguously, and the project's own constitution and
     `.claude/rules/evasion.md` already use "IP"/"IP address" as ordinary business
     vocabulary for this feature (it is literally "ban-evasion detection via hashed IP
     correlation"). Reworded throughout to "IP address" for a more testable,
     unambiguous spec, while still never naming the actual hashing algorithm,
     class, or framework used to compute it.
  2. Removed leftover template instructional HTML comments (`<!-- ACTION REQUIRED... -->`)
     that don't appear in the sibling specs' final `spec.md` files.
- This spec explicitly treats the punishment core (`specs/002-punishment-core/spec.md`)
  and the dual-ELO engine (`specs/001-elo-engine/spec.md`) as *not* triggered by this
  feature — confirmed by reading `IpCorrelationService`, which only logs and publishes a
  `evasion.detected` domain event with no in-repo consumer today, and by searching the
  rest of the backend for any subscriber to that event type (none found). This feature
  is detection/signaling only; deciding and applying any consequence is out of scope here
  and belongs to those sibling specs if/when a consumer is built.
