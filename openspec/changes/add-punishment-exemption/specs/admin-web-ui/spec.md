## ADDED Requirements

### Requirement: Exemption badge and override

The UI SHALL show an exemption badge (linked staff with the role, or permission-exempt) on the player page, the case page and the review queue. The apply, punish and kick dialogs SHALL show the `TARGET_EXEMPT` refusal, and SHALL offer an override field with a required justification to users with the `ADMIN` role only. All texts MUST exist in German and English.

#### Scenario: Moderator sees the refusal

- **WHEN** a moderator applies a ban to an exempt player and the backend refuses it
- **THEN** the dialog shows the refusal and no override field

#### Scenario: Admin overrides with a justification

- **WHEN** an admin fills the override field with a justification and confirms
- **THEN** the request carries `exemptionOverride` and the dialog closes on success

#### Scenario: Badge tooltip

- **WHEN** a user hovers the badge of a permission-exempt player
- **THEN** the tooltip states that a permission removed while the player is offline keeps protecting until the next login

### Requirement: Exemption review queue

The UI SHALL list open exemption reviews for users with the `ADMIN` role, with the owner's badge, the section, the track, the proposed template and the crossing time, and SHALL offer apply and dismiss actions, each with a justification.

#### Scenario: Apply a review

- **WHEN** an admin applies an open review with a justification
- **THEN** the review leaves the open list and the punishment appears in the player's history

#### Scenario: Dismiss a review

- **WHEN** an admin dismisses an open review with a justification
- **THEN** the review leaves the open list and no punishment is created
