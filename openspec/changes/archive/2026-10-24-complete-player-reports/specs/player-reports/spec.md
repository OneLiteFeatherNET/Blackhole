# Spec Delta

## Purpose

Tells reporters what became of their report, keeps a report's evidence while the report is open, and adds the reads the report page needs.

## ADDED Requirements

### Requirement: Feedback for the reporter
Resolving a report as `ACTIONED` or `DISMISSED` SHALL queue one feedback message for the report's `reporterHash`, expiring after `blackhole.report.feedback.ttl` (default 14 days), and announce the reporter's hash on the Redis channel `blackhole.report.feedback`. The message MUST NOT hold the punishment, the resolver or the note; a failure to queue it MUST NOT undo the resolution; any other status queues nothing. The domain event `report.feedback` SHALL carry hashes and ids only.

#### Scenario: Dismissed report
- **WHEN** staff dismisses a report
- **THEN** a feedback message with the outcome `DISMISSED` waits for the reporter

#### Scenario: Under review
- **WHEN** a report is set to `UNDER_REVIEW`
- **THEN** no feedback message is queued

#### Scenario: Queue fails
- **WHEN** the Redis announcement fails
- **THEN** the report is still resolved and the message is still queued

### Requirement: Fetch and acknowledge feedback
`GET /report/feedback/{owner}` SHALL return the unexpired messages of that owner hash, oldest first, and `POST /report/feedback/{identifier}/ack` SHALL delete a message and answer HTTP 204 whether or not it existed. Both routes SHALL be allowed for `PROXY` only. Expired messages MUST NOT be returned and SHALL be deleted by a periodic sweep.

#### Scenario: Pending messages
- **WHEN** the proxy asks for the messages of a reporter with one queued message
- **THEN** the response lists that message and no message of another reporter

#### Scenario: Acknowledging twice
- **WHEN** the proxy acknowledges a message twice
- **THEN** both calls answer HTTP 204 and the message is gone

#### Scenario: Expired message
- **WHEN** a message is older than its expiry
- **THEN** it is not returned and the sweep deletes it

### Requirement: Evidence stays with an open report
Pending evidence whose correlation id starts with `report:` SHALL wait `blackhole.evidence.report-pending-ttl` (default 30 days) instead of `pending-ttl` before the unclaimed-evidence sweep deletes it. When a report is resolved with a punishment template, the evidence items the report references SHALL be attached to the new punishment; a failure to attach MUST NOT undo the resolution.

#### Scenario: Long wait
- **WHEN** report evidence is older than `pending-ttl` but younger than `report-pending-ttl`
- **THEN** the sweep keeps it

#### Scenario: Actioned with a template
- **WHEN** a report with chat evidence is resolved with a template
- **THEN** that evidence belongs to the resulting punishment

### Requirement: Report lookup
`GET /report/{identifier}` SHALL return one report or HTTP 404, and `GET /report?reportedHash=<hash>` SHALL list only the reports against that player, newest first. Both SHALL need `VIEWER`.

#### Scenario: Unknown report
- **WHEN** a viewer asks for a report that does not exist
- **THEN** the response is HTTP 404
