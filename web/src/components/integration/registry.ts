import type { Component } from 'vue'

// A renderer is a reviewed component in this repository, loaded as its own chunk the first time it is needed.
export type RendererLoader = () => Promise<{ default: Component }>

// Reviewed code only: an entry is a new component and a dynamic import, never a URL, a script or a bundle from the API.
// Nothing is registered yet; a new integration renders through the generic table until its component lands.
export const renderers: Readonly<Record<string, RendererLoader>> = Object.freeze({})

// The loader for a key, or null when no reviewed component exists for it (the generic renderer is used then).
export function resolveRenderer(
  key: string,
  table: Readonly<Record<string, RendererLoader>> = renderers,
): RendererLoader | null {
  return Object.prototype.hasOwnProperty.call(table, key) ? table[key]! : null
}
