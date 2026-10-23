# Spec Delta

## Purpose

The admin page links imported bans to templates.

## ADDED Requirements

### Requirement: Import panel requires a template
The vanilla import panel on the admin page SHALL require a template before it offers the preview or the import, offering only templates whose section is not a mute section. It SHALL let the admin map an original reason of the file to another template, send only complete mappings, show the template each reason gets (as a table with the preview and after the import) and name the template in the confirmation. Texts MUST be available in German and English.

#### Scenario: No template chosen
- **WHEN** a list is pasted but no template is chosen
- **THEN** the preview and import buttons are disabled

#### Scenario: Mute templates are not offered
- **WHEN** the template picker is opened
- **THEN** the templates of mute sections are not among the options

#### Scenario: Preview shows the template of each reason
- **WHEN** the preview finishes
- **THEN** a table lists each original reason with its template and count

### Requirement: Imported punishments section
The admin page SHALL have an "Importierte Strafen" (Imported punishments) section listing the imported punishments that have no template, grouped by original reason, with a template picker per group (mute templates not offered) and a link action that is disabled until a template is picked. Applying SHALL ask for confirmation naming the count, the reason and the template, send nothing before it is given, show the backend message in the dialog when refused and otherwise reload the list. The section SHALL say so when every imported punishment has a template.

#### Scenario: Link a group
- **WHEN** an admin picks a template for the group `Hacking` and confirms
- **THEN** the reason and template are sent once and the list is reloaded

#### Scenario: Nothing to link
- **WHEN** no imported punishment is without a template
- **THEN** the section says every imported punishment is linked
