# Spec Delta

## Purpose
Changes how one moderation action is presented in the staff panel. The other requirements of
`admin-web-ui` are unchanged.

## MODIFIED Requirements

### Requirement: Apply a template to a report
"Apply template" SHALL open the apply step as a side sheet of the report page (see `admin-panel-stack`) and not as a dialog. It SHALL offer first the templates whose section kind fits the category (mute sections for chat abuse, ban and server sections for cheating and griefing, all for other), with a switch to show all. It SHALL preview the step and resolve the report as `ACTIONED` with the template through the resolve route in one call; it MUST NOT apply the template separately.

#### Scenario: Apply template
- **WHEN** a moderator picks a template and confirms
- **THEN** one resolve call with status `ACTIONED` and the template is made and no separate apply call
