## 1. Principle text

- [ ] 1.1 Replace principle 1 in the `context:` block of `openspec/config.yaml` with the text in `design.md`, decision 1
- [ ] 1.2 Align the overview paragraph of `CLAUDE.md` that describes PII hashing with the amended principle; change nothing else in `CLAUDE.md`
- [ ] 1.3 Check that `openspec validate --all --strict` passes with the amended config

## 2. Review and record

- [ ] 2.1 Confirm the legal wording with a qualified reviewer (`design.md`, open question 1) before the code PR is merged
- [ ] 2.2 Confirm or create ADR-BLKH-0004 in Outline and link it from the code PR description (`design.md`, open question 2)

## 3. Code PR

- [ ] 3.1 Open the code PR from the merged proposal under the title `docs(openspec): amend the data protection principle`, containing only the changes in 1.1 and 1.2
