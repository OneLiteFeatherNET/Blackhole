# Design tokens

The single source for the colours and brand values that `web/` and `portal/` share. Components stay per app
(see `openspec/changes/redesign-ux/design.md`, section 3).

## Files

- `seed.json`: the MD3 seed colour. The scheme (light and dark, tonal spot) is derived from it.
- `brand.json`: the OLF brand values that are not MD3 roles (the hero gradient).

## Rules

1. Edit a value here, never in a generated file. `web/src/styles/md3-tokens.generated.css` is git-ignored and is
   rewritten by `npm run generate` (run before `dev`, `test` and `build`).
2. A new colour is a new MD3 role or a brand value in this directory. Do not add a colour in a component or a
   stylesheet; the contrast test (`web/tests/contrast.test.ts`) checks the role pairs.
3. Spacing, type and state values live in each app's `main.css`, next to the Tailwind theme that uses them.
   Their names follow design section 2.
4. The parity test (`web/tests/tokens-parity.test.ts`) fails when the generated file differs from the generator
   output for these files. Run `npm run generate` after any change here.
