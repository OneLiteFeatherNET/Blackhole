/** Validation messages sorted by the form field they belong to. */
export interface FieldErrors {
  /** Messages by field name, e.g. `key` or `steps.1.duration` (zero-based step index). */
  fields: Record<string, string>
  /** Messages that name no field of this form. */
  general: string[]
}

type Rule = { pattern: RegExp; field: (match: RegExpMatchArray) => string }

// The backend answers a rejected template with one sentence per violated rule, joined by "; " (see
// TemplateValidator and PunishmentTemplateService). Every sentence starts with the field it is about.
const TEMPLATE_RULES: Rule[] = [
  { pattern: /^step (\d+) needs a duration/, field: (m) => `steps.${Number(m[1]) - 1}.duration` },
  { pattern: /^step (\d+) eloCost/, field: (m) => `steps.${Number(m[1]) - 1}.eloCost` },
  { pattern: /^a template needs at least one step|^a template may have at most/, field: () => 'steps' },
  { pattern: /^key /, field: () => 'key' },
  { pattern: /^a template with key /, field: () => 'key' },
  { pattern: /^reason /, field: () => 'reason' },
  { pattern: /^section |^there is no section /, field: () => 'section' },
  { pattern: /^escalationGroup /, field: () => 'escalationGroup' },
  { pattern: /^escalationWindow /, field: () => 'escalationWindow' },
]

// SectionValidator and ServerPatterns phrase theirs the same way.
const SECTION_RULES: Rule[] = [
  { pattern: /^key |^a section with key /, field: () => 'key' },
  { pattern: /^displayName /, field: () => 'displayName' },
  { pattern: /^enforcement /, field: () => 'enforcement' },
  { pattern: /^(servers? |server pattern|a SERVER section|a section may list)/, field: () => 'servers' },
  { pattern: /^baseScore /, field: () => 'baseScore' },
  { pattern: /^softThreshold /, field: () => 'softThreshold' },
  { pattern: /^softDuration /, field: () => 'softDuration' },
  { pattern: /^permanentThreshold /, field: () => 'permanentThreshold' },
  { pattern: /^pardonScore /, field: () => 'pardonScore' },
  { pattern: /^permanentReason /, field: () => 'permanentReason' },
  { pattern: /^decayPerDay /, field: () => 'decayPerDay' },
]

function sort(message: string, rules: Rule[]): FieldErrors {
  const result: FieldErrors = { fields: {}, general: [] }
  let lastField: string | null = null
  for (const sentence of message.split('; ').map((part) => part.trim()).filter(Boolean)) {
    const rule = rules.find((candidate) => candidate.pattern.test(sentence))
    // One rule's own text may contain "; " (e.g. a server pattern that matches every server), so a
    // fragment that starts no rule continues the sentence before it.
    const field: string | null = rule ? rule.field(sentence.match(rule.pattern) as RegExpMatchArray) : lastField
    if (field === null) {
      result.general.push(sentence)
      continue
    }
    lastField = field
    // A field with several violations shows all of them.
    result.fields[field] = result.fields[field] ? `${result.fields[field]} ${sentence}` : sentence
  }
  return result
}

export const templateFieldErrors = (message: string): FieldErrors => sort(message, TEMPLATE_RULES)
export const sectionFieldErrors = (message: string): FieldErrors => sort(message, SECTION_RULES)
