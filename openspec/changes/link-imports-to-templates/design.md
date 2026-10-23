# Design

## Decisions

1. **Template key as a query parameter, mapping as a JSON part.** The vanilla file is a multipart
   upload already; `templateKey` is a short required query value, `reasonTemplates` an optional text
   part holding a JSON object (reason text to template key). Keys are matched on the trimmed reason
   text; a blank reason is `Imported vanilla ban`, the same text a file without reasons has always got.
2. **Validate every template up front.** All referenced keys (default and mapped) are resolved before
   the first row is written. An unknown key is 404, a `MUTE` section 400, a disabled section 409, a
   malformed mapping 400. Nothing is written on any of these.
3. **Section from the template.** The old fixed `network` section is gone. The duplicate check
   ("the player already has an active ban") uses the template's section.
4. **A record, not an offence.** The punishment is built without `recordEscalation`, so it has no
   group, step or cost and does not count towards escalation; the ELO engine is never called. The
   case timeline gets `punishment.applied` with no step and no scores (the system import identity is
   the actor; the verified caller is `recordedByOid`).
5. **Where the original reason lives.** `importedReason` in the punishment metadata. Rows imported
   before this change keep `reason` there; the re-template action reads `importedReason`, else `reason`.
6. **Re-template (PR 2).** `GET /admin/import/punishments` lists imported punishments without a
   template grouped by original reason. `POST /admin/import/punishments/retemplate` takes a
   `templateKey` and either a `reason` (every eligible punishment with that original reason) or a list
   of `identifiers`. Eligible means: source is the import identity and no template. A punishment that
   already has the target template is `unchanged`; one with another template, or in another section than
   the template, is `skipped` and never overwritten. A second run therefore changes nothing.
7. **Effects of a re-template.** Template and `importedReason` are set, `updateDate` bumped, an active
   punishment publishes `punishment.updated` (the Redis mirror then carries the template reason), the
   profile cache is evicted, and the case gets `punishment.retemplated` (`templateId`, `fromTemplateId`)
   with the token's actor and the dedupe key `retemplated:<punishment>:<template>`. No score, no ELO
   event, no escalation field changes.
8. **Grouping in memory.** The eligible rows are loaded by source and null template and grouped in the
   service by their metadata reason; imports are bounded by one server's ban list, and the reason is a
   JSON metadata value that a portable query would not reach.

## Migrations

None. `punishments.template_id` and the JSON metadata already exist. Existing imported rows are
migrated by the re-template action, not by SQL, so staff choose the template.

## API versions

The import endpoint is hidden. The re-template endpoints are documented and bump the client spec
(PR 2).
