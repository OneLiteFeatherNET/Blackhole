<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { useApi } from '../api/inject'
import type { IntelClass, IntelPolicy } from '../api/types'
import CopyLink from '../components/CopyLink.vue'
import PageShell from '../components/PageShell.vue'
import ListItemMissing from '../components/lists/ListItemMissing.vue'
import PolicyEditDialog from '../components/intel/PolicyEditDialog.vue'
import { isPolicyClass, policyOf } from '../components/intel/policyRows'
import MdButton from '../components/md/MdButton.vue'
import MdSnackbar from '../components/md/MdSnackbar.vue'
import ResourceState from '../components/ResourceState.vue'
import StatusPill from '../components/StatusPill.vue'
import { useResource } from '../composables/useResource'
import { useRoutedDialog } from '../router/routedDialog'
import { useSessionStore } from '../stores/session'

// One login class of the VPN and hosting policy. An administrator changes it in the child route `edit`.
const props = defineProps<{ classification: string }>()

const { t } = useI18n()
const api = useApi()
const session = useSessionStore()

// A path that names no class asks the server for nothing.
const policies = useResource(() => api.intelPolicies(), { immediate: isPolicyClass(props.classification) })
watch(
  () => props.classification,
  () => {
    if (isPolicyClass(props.classification)) void policies.reload()
  },
)
const edit = useRoutedDialog({ parent: 'intel-policy-class', child: 'intel-policy-class-edit' })
const message = ref('')
const tones = { ALLOW: 'primary', FLAG: 'tertiary', DENY: 'error' } as const

/** The class in the path, or null when the path names no class (then the page says so). */
const cls = computed<IntelClass | null>(() => (isPolicyClass(props.classification) ? props.classification : null))
const known = computed(() => cls.value !== null)
const current = computed<IntelPolicy | null>(() => (cls.value && policies.data.value ? policyOf(policies.data.value, cls.value) : null))

async function saved(text: string): Promise<void> {
  await edit.close()
  message.value = text
  await policies.reload()
}
</script>

<template>
  <PageShell :title="known ? t(`intel.classes.${props.classification}`) : t('intel.policy.title')" :description="t('intel.policy.intro')">
    <template #actions>
      <CopyLink />
    </template>

    <p class="mb-4">
      <RouterLink :to="{ name: 'intel-policy' }" data-testid="back-to-list" class="inline-flex min-h-12 items-center text-primary underline underline-offset-2 focus-ring">
        {{ t('intel.policy.backToList') }}
      </RouterLink>
    </p>

    <ListItemMissing v-if="!known" :back-to="{ name: 'intel-policy' }" :back-label="t('intel.policy.backToList')" />

    <ResourceState
      v-else
      :loading="policies.loading.value && policies.data.value === null"
      :error="policies.error.value"
      :has-data="policies.data.value !== null"
      @retry="policies.reload"
    >
      <div v-if="current" class="flex flex-col gap-4">
        <p class="text-body-large text-on-surface-variant">{{ t(`intel.classHint.${props.classification}`) }}</p>
        <div class="flex flex-wrap items-center gap-3">
          <StatusPill :label="t(`intel.policies.${current}`)" :tone="tones[current]" data-testid="policy-current" />
          <MdButton
            v-if="session.administrator"
            as="link"
            data-testid="policy-change"
            :to="{ name: 'intel-policy-class-edit', params: { classification: props.classification } }"
          >
            {{ t('intel.policy.change') }}
          </MdButton>
        </div>
      </div>
    </ResourceState>

    <PolicyEditDialog
      v-if="cls && current"
      :open="edit.open.value && session.administrator"
      :classification="cls"
      :current="current"
      :administrator="session.administrator"
      @close="edit.close"
      @saved="saved"
    />

    <MdSnackbar :open="message !== ''" :message="message" @close="message = ''" />
  </PageShell>
</template>
