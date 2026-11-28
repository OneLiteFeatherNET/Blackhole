import type { InjectionKey } from 'vue'

/**
 * What a configuration page offers the dialog routes under it. A dialog calls `changed` after a successful change:
 * the page reloads its data and shows the message. `reload` only reloads the data (a conflict shows the newer
 * version behind the dialog). Without a page (a dialog mounted on its own) nothing happens.
 */
export interface ConfigPage {
  changed(message: string): Promise<void>
  reload(): Promise<void>
}

export const CONFIG_PAGE: InjectionKey<ConfigPage> = Symbol('config page')

export const NO_CONFIG_PAGE: ConfigPage = {
  changed: async () => {},
  reload: async () => {},
}
