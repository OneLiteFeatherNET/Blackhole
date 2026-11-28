# Design tokens

The single source for the colours and brand values that `web/` and `portal/` share. Components stay per app
(see `openspec/changes/redesign-ux/design.md`, section 3).

## Files

- `seed.json`: the MD3 source colour, OLF Pink `#EC008B`, and the `accents` (secondary, tertiary, success, warning).
  Both `web/` and `portal/` read it, so the two apps share one brand palette.
- `brand.json`: the OLF brand values that are not MD3 roles (the hero gradient).

## The scheme

Light and dark are built by `web/scripts/generate-md3-tokens.mjs` (portal's generator mirrors it) with the
`DynamicScheme` of `@material/material-color-utilities`, from explicit palettes:

- primary: the seed's own palette, so primary is the OLF pink (hue about 325 to 360 degrees).
- secondary `#7B2CBF` (violet) and tertiary `#27A9E1` (cyan): brand accents, so the three hues are apart.
- surfaces and outlines: neutral palettes at the seed's hue with chroma 2 (neutral) and 4 (neutral variant). A surface
  stays near-neutral (channel spread at most 10) instead of tinting pink as the Vibrant scheme did.
- success and warning: not MD3 core roles. Each accent is harmonised to the seed with `Blend.harmonize`, then takes the
  MD3 accent tones (light: 40, 100, 90, 10; dark: 80, 20, 30, 90). Each role has `on-`, `-container` and
  `on-…-container`.

Use `error` only for a failure or an urgent or active state; `success` for a done state; `warning` for a waiting or
high-priority state.

## Rules

1. Edit a value here, never in a generated file. `web/src/styles/md3-tokens.generated.css` is git-ignored and is
   rewritten by `npm run generate` (run before `dev`, `test` and `build`).
2. A new colour is a new MD3 role or a brand value in this directory. Do not add a colour in a component or a
   stylesheet; the contrast test (`web/tests/contrast.test.ts`) checks the role pairs.
3. Spacing, type and state values live in each app's `main.css`, next to the Tailwind theme that uses them.
   Their names follow design section 2.
4. The parity test (`web/tests/tokens-parity.test.ts`) fails when the generated file differs from the generator
   output for these files. Run `npm run generate` after any change here.
5. The contrast tests (`web/tests/contrast.test.ts`, `portal/src/styles/contrast.test.ts`) check every role pair in
   both themes, and that the surfaces stay neutral and the three accents stay apart.
