import type { InjectionKey } from 'vue'

/** Writes text to the clipboard. The panel never reads the clipboard. */
export interface ClipboardWriter {
  writeText(text: string): Promise<void>
}

/** Injected by the app and by tests; the default is the browser clipboard. */
export const CLIPBOARD_KEY: InjectionKey<ClipboardWriter> = Symbol('clipboard')

export function browserClipboard(): ClipboardWriter {
  return { writeText: (text) => navigator.clipboard.writeText(text) }
}
