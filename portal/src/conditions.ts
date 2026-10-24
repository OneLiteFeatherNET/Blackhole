/**
 * The appeal conditions are plain text: a blank line separates paragraphs and a line starting with "- " is a
 * bullet. Nothing else is interpreted, so no markup can be built from the text; the pieces returned here are
 * rendered as text nodes only (`ConditionsText.vue`).
 */
export type ConditionsBlock = { kind: 'paragraph'; text: string } | { kind: 'list'; items: string[] }

export function parseConditions(text: string): ConditionsBlock[] {
  const blocks: ConditionsBlock[] = []
  let paragraph: string[] = []
  let list: string[] | null = null

  const endParagraph = () => {
    if (paragraph.length > 0) blocks.push({ kind: 'paragraph', text: paragraph.join(' ') })
    paragraph = []
  }
  const endList = () => {
    if (list !== null) blocks.push({ kind: 'list', items: list })
    list = null
  }

  for (const raw of text.replace(/\r\n?/g, '\n').split('\n')) {
    const line = raw.trim()
    if (line === '') {
      endParagraph()
      endList()
    } else if (line.startsWith('- ')) {
      endParagraph()
      list ??= []
      list.push(line.slice(2).trim())
    } else {
      endList()
      paragraph.push(line)
    }
  }
  endParagraph()
  endList()
  return blocks
}
