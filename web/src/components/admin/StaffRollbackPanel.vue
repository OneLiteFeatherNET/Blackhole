<script setup lang="ts">
// The staff rollback screen (ADMIN only, see AdminView). Every state is an address (`restructure-staff-panel`, decision 3):
// `/admin/rollback/new` previews a rollback in a dialog, `/admin/rollback/:id` shows its job, and `/execute` and `/cancel`
// are the two confirmations over the job. The page only renders them; the backend enforces the role anyway.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { useSessionStore } from '../../stores/session'
import MdButton from '../md/MdButton.vue'
import RollbackCancelDialog from './RollbackCancelDialog.vue'
import RollbackExecuteDialog from './RollbackExecuteDialog.vue'
import RollbackJobPanel from './RollbackJobPanel.vue'
import RollbackNewDialog from './RollbackNewDialog.vue'
import type { RollbackTimer } from './rollback'

const props = defineProps<{ timer?: RollbackTimer }>()

const { t } = useI18n()
const route = useRoute()
const session = useSessionStore()

const jobId = computed(() => (typeof route.params.id === 'string' ? route.params.id : null))
</script>

<template>
  <section v-if="session.administrator" aria-labelledby="rollback-title" class="flex max-w-2xl flex-col gap-4" data-testid="staff-rollback">
    <h2 id="rollback-title" class="text-title-medium">{{ t('rollback.title') }}</h2>
    <p class="text-body-large text-on-surface-variant">{{ t('rollback.intro') }}</p>

    <div v-if="jobId === null">
      <MdButton variant="tonal" :to="{ name: 'admin-rollback-new' }" as="link" data-testid="rollback-new">{{ t('rollback.jobs.newLink') }}</MdButton>
    </div>
    <RollbackJobPanel v-else :id="jobId" :timer="props.timer" />

    <RollbackNewDialog v-if="route.name === 'admin-rollback-new'" />
    <RollbackExecuteDialog v-if="jobId !== null && route.name === 'admin-rollback-execute'" :id="jobId" />
    <RollbackCancelDialog v-if="jobId !== null && route.name === 'admin-rollback-cancel'" :id="jobId" />
  </section>
</template>
