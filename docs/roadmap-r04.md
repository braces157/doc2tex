# R04 editor design and verification

Implemented 16 October 2026, Asia/Bangkok (UTC+7), after R03 was reviewed and merged in [PR #11](https://github.com/braces157/doc2tex/pull/11). PR review and final-head CI remain required before merging.

## Editor choice

Use CodeMirror 6 with the official view, state, commands, search, language and legacy-modes packages. Registry metadata checked during implementation reports MIT licensing and recent modifications (view/language/legacy-modes: 7 October 2026; state: 22 September; commands: 15 September; search: 31 August). Exact package versions are pinned in package.json. The production license inventory has been regenerated (138 packages), and npm audit reports zero production vulnerabilities. Only the `stex` legacy mode is imported for LaTeX highlighting. The editor loads on demand in a separate 316.65 kB minified / 102.28 kB gzip chunk; the initial application chunk is 489.24 kB / 145.27 kB gzip. Vite reports no bundle-size warnings.

The [system guide](https://codemirror.net/docs/guide/) describes its line-tree document model and viewport rendering; the [reference manual](https://codemirror.net/docs/ref/) documents history, search and navigation commands. Follow its [Tab accessibility guidance](https://codemirror.net/examples/tab/): retain normal Tab focus movement and expose indentation buttons/standard commands instead of introducing a keyboard trap. Preserve the accessible name “LaTeX source editor.”

## Ownership and checks

Keep source/revision/draft ownership in `useSourceDocument`. The adapter owns only editor view state and local undo/redo. External source loads/restores establish a new editor baseline; document switches cannot carry undo text into another job. Expose focus/reveal-line navigation for subsequent diagnostic and review tickets.

The source tab stays mounted while PDF/Review tabs are visible, preserving local undo history. The editor uses read-only state plus a noneditable content surface during save/build. Its built-in history and replacement commands also check that state. Visible Ctrl/Cmd hints accompany Save and Build; both use the same readiness, compiler-availability and busy guards as the buttons.

## Local observations

Windows, Intel Core Ultra 5 225H, approximately 31.4 GiB RAM, Node 25.5.0, Java 25.0.1, Chromium 156.0.8078.4. A synthetic 2,095,104-byte source (2 KiB under 2 MiB) was edited, searched to its final marker, scrolled and saved with exact content comparison. With two browser workers, desktop typing/search took 79/222 ms and the iPhone 13 Chromium emulation took 109/233 ms. These event-to-visible observations include test overhead and are not device-independent performance guarantees. Test output retains the measurements as JSON.

Explicit automated checks cover editor accessible name/description, toolbar labels, Tab leaving the editor, line clamping and focus, highlighted tokens, find/replace, undo/redo, wrapping with no page overflow, and desktop/mobile internal scrolling. Thai/CJK Unicode insertion and synthetic composition events verify shortcut suppression and exact saved text. A live operating-system IME, native iPhone browser, and live screen-reader session were not available; those manual sessions remain unperformed, rather than being implied by emulation and accessibility labels.

Verify find/replace, undo/redo, indentation, clamped navigation, wrap behavior, keyboard focus and mobile scrolling. Save/Build shortcuts must share button readiness/busy guards, support Ctrl/Cmd, and ignore modal or IME composition input. Record local typing/search observations with a synthetic source near 2 MiB. Automated accessible-name and Thai/CJK composition checks are distinct from live assistive-technology/IME sessions and must be reported accurately. Run existing recovery/conflict workflows, lint/build, browser tests and full backend/PDF verification before review.

## Verification results

- `npm --prefix frontend run lint` and `npm --prefix frontend run build`: passed, no warnings.
- `npm --prefix frontend run test:e2e -- --workers=2`: 70 passed across desktop/mobile Chromium on the final implementation. Includes existing library, lifecycle, draft/conflict and saved-version workflows, plus editor tools, history across tab switches, both platform shortcuts, read-only undo protection, composition guards and near-limit source checks. Browser APIs are synthetic mocks; real PDF coverage comes from the backend suite.
- `./mvnw.cmd -B verify '-Ddoc2tex.test-pdf=true'`: 57 passed, zero skipped, including actual Pandoc/Tectonic corpus conversions and restored-history PDF assertions. Tests use temporary synthetic storage, independently of real `.data` documents.
- `git diff --check`, production license collection and production dependency audit: passed. Desktop/mobile editor screenshots were inspected; search/tools fit, highlighting and Unicode are readable, and the editor scrolls internally.
