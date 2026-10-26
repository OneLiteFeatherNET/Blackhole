import type { SelectOption } from '../md/MdSelect.vue'
import type { CloudNetCatalogue, KickScope, Section } from '../../api/types'

// A plain server name; a section may list patterns (with wildcards), which are no single server to kick from.
const SERVER_NAME = /^[A-Za-z0-9_.-]+$/

/** The servers a kick can name: those the sections list and the running services CloudNet reports, sorted, once each. */
export function serverTargets(sections: Section[], catalogue: CloudNetCatalogue | null): SelectOption[] {
  const names = new Set<string>()
  for (const section of sections) for (const server of section.servers) if (SERVER_NAME.test(server)) names.add(server)
  for (const service of catalogue?.services ?? []) if (service.running && SERVER_NAME.test(service.name)) names.add(service.name)
  return [...names].sort((a, b) => a.localeCompare(b)).map((name) => ({ value: name, label: name }))
}

/** The sections a kick can name: enabled ones only (the backend refuses a disabled section). */
export function sectionTargets(sections: Section[]): SelectOption[] {
  return sections.filter((section) => section.enabled).map((section) => ({ value: section.key, label: section.displayName }))
}

/** The targets for a scope; a network kick has none. */
export function targetsFor(scope: KickScope, sections: Section[], catalogue: CloudNetCatalogue | null): SelectOption[] {
  if (scope === 'SERVER') return serverTargets(sections, catalogue)
  if (scope === 'SECTION') return sectionTargets(sections)
  return []
}

/** A fresh key per attempt that has to be retried as the same kick; letters, digits and '-' only. */
export function newIdempotencyKey(): string {
  return globalThis.crypto.randomUUID()
}
