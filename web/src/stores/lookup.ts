import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * Remembers the player name of the current lookup so the detail page can show it. Deliberately held
 * in memory only: names and owner hashes must never reach browser storage, a reload forgets them.
 */
export const useLookupStore = defineStore('lookup', () => {
  const names = ref<Record<string, string>>({})

  function remember(owner: string, name: string): void {
    names.value = { ...names.value, [owner]: name }
  }

  function nameOf(owner: string): string | null {
    return names.value[owner] ?? null
  }

  return { remember, nameOf }
})
