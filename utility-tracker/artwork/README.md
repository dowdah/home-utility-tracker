# Water and electricity launcher mark

Original native vector: `launcher-mark.svg`. One even-odd path forms a water droplet and a fully transparent lightning cutout. No text, grid, pre-rendered shadow or adaptive mask is baked into the foreground.

- Canvas: 108 × 108dp. All foreground pixels fit the centered 66dp safe circle.
- Light: background `#EAF7FF`, mark `#006E8A`.
- Dark: background `#09252F`, mark `#78D9EC`.
- Monochrome: the same silhouette and transparent cutout, solid black; Android/launcher supplies the tint.
- Legacy density PNGs: 48/72/96/144/192px, regular and round, each with day/night variants. These include masks because legacy launchers do not apply adaptive masks.

`launcher-preview.png` shows rounded and circular masks plus 48/32/24px samples. The purple monochrome row is an illustrative tint, not a promised launcher palette. `launcher-light.svg` and `launcher-dark.svg` are portable preview assets; the app uses Android vectors and color resources.

From the repository root, run `node tools/generate_launcher_icons.cjs` with `sharp` available on Node's module search path to regenerate the Android resources and previews. `LauncherIconTest` renders the installed resources, checks safe-area/transparent-cutout/monochrome consistency and decodes every packaged density fallback. Actual launcher/splash checks are recorded separately in the release acceptance report.
