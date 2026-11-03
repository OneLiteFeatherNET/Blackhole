<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute } from 'vue-router'
import { useRoutedDialog } from '../router/routedDialog'
import MdButton from './md/MdButton.vue'
import MdDialog from './md/MdDialog.vue'

// A create, edit or action dialog whose open state is its child route (`restructure-staff-panel`, decision 3).
// The page stays rendered behind it. `notFound` is decided by the page: a missing item shows the not-found text
// and no form, never an empty dialog.
const props = defineProps<{ parent: string; child: string; headline: string; notFound?: boolean; failed?: boolean }>()

const { t } = useI18n()
const route = useRoute()
const dialog = useRoutedDialog({ parent: props.parent, child: props.child })
</script>

<template>
  <MdDialog :open="dialog.open.value" :headline="props.headline" wide @close="dialog.close">
    <template v-if="props.notFound">
      <p>{{ t('routedDialog.notFound') }}</p>
      <RouterLink
        :to="{ name: props.parent, params: route.params }"
        data-testid="routed-dialog-back"
        class="mt-4 inline-block text-primary underline underline-offset-2 outline-none focus-visible:outline-2 focus-visible:outline-primary"
      >
        {{ t('routedDialog.backToPage') }}
      </RouterLink>
    </template>
    <slot v-else />
    <p v-if="props.failed" role="alert" data-testid="routed-dialog-failed" class="mt-4 text-error">
      {{ t('routedDialog.actionFailed') }}
    </p>
    <template #actions>
      <MdButton variant="text" data-testid="routed-dialog-close" @click="dialog.close">{{ t('routedDialog.close') }}</MdButton>
      <slot v-if="!props.notFound" name="actions" />
    </template>
  </MdDialog>
</template>
