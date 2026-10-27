# Spec Delta

## Purpose

Defines how the staff panel explains its domain terms (client rule kinds, actions, scopes and the
spoofability warning, and the punishment terms) where they are used, in plain language for staff who
are not developers.

## ADDED Requirements

### Requirement: Help next to the term it explains
A field or label whose term is a domain concept SHALL show a help control: an info icon with a
supporting text under the field and, on focus or hover, a rich tooltip with the definition. The control
SHALL be reachable by keyboard and its tooltip SHALL stay open while hovered or focused.

#### Scenario: Keyboard user reads help
- **WHEN** a moderator tabs to the info control of the field "Kind" and presses Enter
- **THEN** the definition of the concept is shown and can be read with a screen reader

#### Scenario: Tooltip stays while hovered
- **WHEN** the pointer moves from the info control onto the tooltip
- **THEN** the tooltip does not close

### Requirement: Concept disclosure with an example and a link
Each glossary concept SHALL offer a "What is this?" disclosure that shows a definition of at most three sentences, one example, and a link to the staff guide section for that concept. The disclosure SHALL be collapsed by default.

#### Scenario: Open the disclosure
- **WHEN** a moderator expands "What is this?" for the concept "Brand"
- **THEN** the definition, the example and the link to the staff guide section for brands are shown

### Requirement: Spoofability is stated where a value is chosen
Any step that sets a brand, mod or channel SHALL show the spoofability statement "The client controls this value and can fake it. A hit is a signal, not proof." next to the choice, not only on the final review.

#### Scenario: Statement at the choice
- **WHEN** a moderator chooses the kind "Brand"
- **THEN** the spoofability statement is shown beside the kind choice

### Requirement: Glossary is complete for client rules
The glossary SHALL define these concepts for client rules: rule, key, kind (brand, mod, plugin channel, protocol version, command), match pattern, negate, scope (whole network, limited to servers), server section, target kind, action (notify, deny, apply a punishment), deny message, punishment template, acknowledgement of a spoofable value, hide from tab completion, enabled, hit, enforced and bypassed.

#### Scenario: Every concept has help
- **WHEN** the glossary is checked against the client rule editor fields and options
- **THEN** every field label and option value of the editor has a glossary entry, and the test fails for a missing one

### Requirement: Help texts are staff UI copy in the app bundles
The help texts SHALL live in the `en` and `de` message bundles of the panel and not in the translation catalog of the backend. Each concept key SHALL exist in both bundles, and a missing German text SHALL fail the test that checks key parity.

#### Scenario: Key parity
- **WHEN** the German bundle lacks a help key that exists in the English bundle
- **THEN** the key parity test fails and names the missing key

### Requirement: Help text is plain language
Help texts SHALL avoid implementation terms (regular expressions, protocol numbers as the only explanation, plugin identifiers) and SHALL explain a term in the words a moderator uses for the effect on a player. A text that uses a technical term SHALL define it in the same sentence.

#### Scenario: Protocol explained
- **WHEN** the help for "Protocol version" is shown
- **THEN** it explains that the number identifies the game version the player's client speaks and that a player on a newer client may show an older number behind a version-translating proxy
