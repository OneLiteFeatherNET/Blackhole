import { computed, type ComputedRef } from 'vue'
import { useApi } from '../../api/inject'
import type { ApiError, Section, Template } from '../../api/types'
import { useResource } from '../../composables/useResource'
import type { SelectOption } from '../md/MdSelect.vue'

export interface ImportTemplateOptions {
  loading: ComputedRef<boolean>
  error: ComputedRef<ApiError | null>
  /** The templates a vanilla ban may be linked to: every template whose section is not a mute section. */
  options: ComputedRef<SelectOption[]>
  reload: () => Promise<void>
}

/** Vanilla bans are bans, so templates of mute sections are not offered (the backend refuses them as well). */
export function useImportTemplateOptions(): ImportTemplateOptions {
  const api = useApi()
  const templates = useResource<Template[]>(() => api.templates())
  const sections = useResource<Section[]>(() => api.sections())

  const options = computed<SelectOption[]>(() => {
    const muteSections = new Set((sections.data.value ?? []).filter((section) => section.enforcement === 'MUTE').map((section) => section.key))
    return (templates.data.value ?? [])
      .filter((template) => !muteSections.has(template.section))
      .map((template) => ({ value: template.key, label: template.key, description: template.reason }))
      .sort((a, b) => a.label.localeCompare(b.label))
  })

  return {
    loading: computed(() => templates.loading.value || sections.loading.value),
    error: computed(() => templates.error.value ?? sections.error.value),
    options,
    reload: async () => {
      await Promise.all([templates.reload(), sections.reload()])
    },
  }
}
