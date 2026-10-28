/**
 * Makes everything outside `element` inert (no focus, hidden from assistive technology), by marking the
 * siblings of the element and of each of its ancestors up to <body>. Returns a function that removes only the
 * `inert` attributes this call added; an element that was already inert stays inert.
 */
export function inertBehind(element: HTMLElement): () => void {
  const added: HTMLElement[] = []
  let node: HTMLElement = element
  while (node.parentElement && node !== document.body) {
    for (const sibling of Array.from(node.parentElement.children) as HTMLElement[]) {
      if (sibling === node || sibling.hasAttribute('inert')) continue
      sibling.setAttribute('inert', '')
      added.push(sibling)
    }
    node = node.parentElement
  }
  return () => {
    for (const sibling of added) sibling.removeAttribute('inert')
  }
}
