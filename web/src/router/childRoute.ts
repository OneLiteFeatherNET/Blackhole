import type { Component } from 'vue'

/**
 * The component of a child route that is a dialog (`restructure-staff-panel`, decision 3). The parent page is
 * never given a RouterView: it reads the child from the route and renders its dialog over itself, so the parent
 * instance stays mounted while the dialog is open. This component is never rendered.
 */
export const DIALOG_ROUTE: Component = { render: () => null }
