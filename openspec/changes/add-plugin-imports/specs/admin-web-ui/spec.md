## MODIFIED Requirements

### Requirement: Import panel requires a template
The import panel on the admin page SHALL let the admin choose a source (vanilla list, LiteBans, AdvancedBan or BanManager). For the vanilla list it SHALL take the list as before. For a plugin source it SHALL take one file input per table listed for that source, a source label and an optional mute template. The panel SHALL require a template before it offers the preview or the import, offering only templates whose section is not a mute section for ban-type imports, and only mute-section templates for the mute template. It SHALL let the admin map an original reason of the file to another template, send only complete mappings, show the template each reason gets (as a table with the preview and after the import), show the counts of the summary per file, list the pending IP records separately, and name the template in the confirmation. Texts MUST be available in German and English.

#### Scenario: No template chosen
- **WHEN** a file is chosen but no template is chosen
- **THEN** the preview and import buttons are disabled

#### Scenario: Mute templates are not offered
- **WHEN** the ban template picker is opened
- **THEN** the templates of mute sections are not among the options

#### Scenario: Plugin source asks for each table
- **WHEN** the admin picks LiteBans as the source
- **THEN** the panel shows one file input each for `bans`, `mutes`, `warnings`, `kicks` and `history`, and the preview button stays disabled until the required ones are chosen

#### Scenario: Preview shows the template of each reason
- **WHEN** the preview finishes
- **THEN** a table lists each original reason with its template and count

#### Scenario: Pending IP records are shown
- **WHEN** a plugin import preview reports pending IP records
- **THEN** the panel shows their count and says they are not enforced yet

### Requirement: Imported punishments section
The admin page SHALL have an "Importierte Strafen" (Imported punishments) section listing the imported punishments that have no template, from every import source, grouped by original reason, with a template picker per group (mute templates offered only for mute imports) and a link action that is disabled until a template is picked. Each group SHALL show its import source and the legacy staff name where the import has one, labelled as not linked to a current staff account. The section SHALL say so when every imported punishment has a template.

#### Scenario: Nothing to link
- **WHEN** no imported punishment is without a template
- **THEN** the section says every imported punishment is linked

#### Scenario: Source and legacy staff shown
- **WHEN** a LiteBans group is listed
- **THEN** the group shows the source `LiteBans` and the legacy staff name, marked as not a linked staff account
