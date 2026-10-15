# Customer-focused product roadmap

Created: 16 October 2026. Status: implementation in progress; see the progress ledger for delivered work and verification evidence.

## Product decision

Make doc2TeX the local workspace where a student, researcher, or technical writer can turn a Word draft into a LaTeX project they can confidently finish and export.

As that customer, my first request would be: **“Help me fix the conversion without losing my work.”** My next requests would be: “Let me find my documents,” and “Help me get this into the format I need.”

Prioritize a complete DOCX workflow before expanding input formats. The proposed first release combines a usable document library, saved versions, and a better source editor. The second release makes conversion problems easier to locate and resolve. The third supports more publication needs and easier installation.

This is a product hypothesis based on repository inspection, not customer interviews, competitive research, or a live usability study. Validate it with representative users before investing in the larger items. Estimates below describe relative complexity, not delivery dates.

## What already exists

Do not rebuild these features as new work:

- DOCX upload, sample document, Article/Report selection, and bounded background conversion.
- Editable source with line numbers, save/copy, local draft recovery, source-load retry, and revision conflict protection.
- Separate PDF compilation, page navigation, compiler log access, and source/PDF/ZIP downloads.
- Persistent recent history, conversion review warnings, and conditional multilingual font exports.
- Backend conversion fixtures and mocked desktop/mobile browser workflow tests.

The relevant baseline is [README](../README.md), [development guide](development.md), and [conversion evidence](conversion-test-results.md). Source inspection found these concrete gaps:

| Customer friction | Current evidence | Proposed response |
| --- | --- | --- |
| “Where did my older conversion go?” | `JobStore.list()` returns at most 30 jobs; the desktop sidebar renders 10. There is no full-library search or pagination. | R01 |
| “How do I rename or remove this document?” | The API has no rename/delete routes; cleanup currently requires removing directories manually. | R02 |
| “I saved a mistake. Can I go back?” | `saveSource()` replaces `main.tex`; the revision number prevents conflicts but is not saved-version history. Browser drafts protect unsaved work. | R03 |
| “Editing a long LaTeX file is difficult.” | `SourceWorkspace` uses a plain textarea with a separate line-number gutter. | R04 |
| “The PDF failed. Which line should I fix?” | The UI links to raw logs; compiler warnings are strings without source navigation. | R05 |
| “What exactly should I check against Word?” | Review findings describe categories, without source excerpts, persistent review decisions, or element navigation. | R06 |
| “Why does my figure have two numbers?” | The corpus documents duplicate pre-numbered captions; the inspector warns but does not provide a correction workflow. | R07 |
| “I need A4, different margins, or a title page.” | The converter allows Article/Report and hardcodes one-inch margins. | R08 |
| “How do I use my citation library?” | Bibliography reconstruction is explicitly outside the current scope; there is no `.bib` attachment workflow. | R09 |
| “Can I get started without a developer environment?” | The documented setup requires Java, Node, scripts, and initial tool/package downloads. | R10 |

## Customers and outcomes

| Customer | Job to complete | Evidence of a useful improvement |
| --- | --- | --- |
| Student new to LaTeX | Convert an assignment and fix a failed build | Can locate an error, edit, rebuild, and recover an earlier saved version without terminal use |
| Researcher | Move a paper with math, figures, tables, and citations into LaTeX | Can review risky content and export a self-contained project with its bibliography |
| Returning technical writer | Reopen and improve multiple reports | Can find an older document, distinguish copies, and remove unwanted originals through the UI |

Collect feedback through opt-in sessions and synthetic documents. Do not add document-content telemetry as part of this roadmap.

## Release sequence

S = bounded change, M = several coordinated changes, L = substantial persistence/conversion work. Split L items into the listed increments. Dependencies are requirements; priority alone does not authorize skipping them.

| Order | ID | Feature | Priority | Size | Depends on | Release |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | R01 | Searchable document library | P0 | M | Existing jobs API | 1 |
| 2 | R02 | Rename and delete documents | P0 | M | R01 | 1 |
| 3 | R03 | Saved versions and restore | P0 | L | Existing revision checks | 1 |
| 4 | R04 | LaTeX editing tools | P0 | M | Existing draft recovery | 1 |
| 5 | R05 | Actionable compiler diagnostics | P1 | M | R04 | 2 |
| 6 | R06 | Guided conversion review | P1 | L | R04; use R05 diagnostics when available | 2 |
| 7 | R07 | Caption numbering and reference repair | P1 | L | R03, R06 | 2 |
| 8 | R08 | Document layout presets | P2 | M | R03 | 3 |
| 9 | R09 | Bibliography import and citation insertion | P2 | L | R03, R04, R05 | 3 |
| 10 | R10 | Packaged local app and setup diagnostics | P2 | L | Stable Release 1 workflow | 3 |

R03 and R04 can be implemented independently of the library. The order above is a recommended single-agent sequence, not a request to start parallel agents. R10 may move earlier if onboarding observations show setup prevents people from trying the app.

### Release 1 gate: useful for repeat work

A customer can find conversion 35, rename it, edit and save twice, restore the first saved version, rebuild/export, and delete an unwanted conversion. Repeat after a server restart and in a second tab; existing draft recovery and conflict protection must still work.

### Release 2 gate: useful when conversion needs repair

A customer can navigate from a compiler error to source, compare a risky table/caption with an original-text excerpt, record what they reviewed, and correct a duplicate caption without silently changing prose. Content-preservation fixtures remain green. Review completion must never be described as proof of fidelity.

### Release 3 gate: useful beyond the default template

A customer can select a layout, attach a bibliography, insert a citation, compile, and export the necessary assets. A clean Windows environment can start the packaged app through documented steps. Expand packaged OS support only after it is tested.

## Implementation tickets

Paths below are repository-relative. New route and file names are proposals; preserve existing API behavior or explicitly migrate every caller and test. Check the code again before implementation because this document may outlive the current architecture.

### R01 — Find every saved conversion

**Customer story:** “I converted this last month; I should not need its job ID to reopen it.”

**Build:** Keep the recent sidebar, add an “All documents” view with filename search, status filter, newest/oldest sorting, and pagination. Include original filename, creation date, template, and status so repeated uploads are distinguishable. Search the entire stored collection on the server, before pagination.

**Implementation:** Add an explicit paginated route such as `GET /api/library?query=&status=&sort=&page=&size=` returning `{items, page, size, total}`. Keep `GET /api/jobs` compatible for recent-history callers. Use a stable tie-breaker on job ID when dates match. Cap page size and validate query parameters. Restore the active document by ID, including when it is absent from the recent 30. Reuse the source-switch/discard flow.

**Likely files:** `JobStore.java`, `ConversionController.java`, `frontend/src/App.tsx`, `WorkspaceView.tsx`, `api.ts`; new `DocumentLibrary.tsx`.

**Accept when:**

- With 65 stored jobs, all are reachable and an older-than-30 filename is searchable after restart.
- Sorting is deterministic, filters combine correctly, and empty/error/loading states are usable.
- Opening a document with unsaved edits still invokes the existing discard/recovery behavior.
- Library controls work by keyboard and at the existing mobile viewport.

**Verify:** Backend pagination/filter/restart tests and a mocked browser workflow finding and reopening an older document. Include active-document restoration outside the recent list.

### R02 — Rename and delete safely

**Customer story:** “I want to organize my drafts and remove documents I no longer want retained.”

**Build:** Persist an optional display name separate from the original filename. Add individual deletion with a dialog that names the document and explains that its original, source, versions, media, and previews will be removed. No automatic expiry or bulk deletion in this increment.

**Implementation:** Add metadata-update and deletion endpoints. Load old metadata with a filename fallback. Coordinate deletion with workers, saves, previews, and active archive streams through a per-job lifecycle lock or equivalent; reject deletion while an operation holds the job. Validate the resolved UUID directory and reject symlink/reparse-point traversal. Preserve a recoverable state if filesystem cleanup fails; do not report a successful deletion while leaving an accessible job in memory. Clear this browser's matching draft/active-job keys only after server success. Other tabs must handle the deleted job without recreating it or silently losing an in-memory draft.

**Likely files:** `Job.java`, `JobStore.java`, `ConversionService.java`, `ConversionController.java`, `api.ts`, `App.tsx`, `drafts.ts`, library UI from R01.

**Accept when:**

- Renaming survives restart and does not change source, job ID, or original filename.
- Confirmed deletion removes all job-owned files and listing entries; cancelling changes nothing.
- Busy-job deletion is rejected clearly; another tab receives an actionable missing-document state and can download its unsaved text.
- A cleanup failure is reported and can be retried; traversal or linked directories cannot delete files outside the job.

**Verify:** Temporary-directory lifecycle tests, restart/cleanup failure tests, and browser cancel/confirm/other-tab cases. Never test deletion against the user's real `.data` directory.

### R03 — Restore an earlier saved version

**Customer story:** “I tried a fix, saved it, and made things worse. Let me undo that save.”

**Build in increments:** (a) durable immutable source snapshots, (b) version list/read-only preview/download, (c) restore as a new current revision. Keep the original converted source as version 0 for new jobs. For existing jobs, snapshot the current source and clearly state that earlier overwritten versions are unavailable.

**Implementation:** Preserve the existing optimistic revision check. Add version-list, version-source, and restore operations; restoration requires the expected current revision. A restore creates a new increasing revision, invalidates the current PDF, and leaves prior snapshots unchanged. Design recovery for interruption between source, snapshot, and metadata writes before implementing: startup must select a consistent committed state. Do not reconstruct history from preview folders. Set a documented bounded-history policy with a clear storage-limit response; do not silently erase protected history to make a save succeed.

**Likely files:** `ConversionService.java`, `JobStore.java`, `Job.java`, controller; new version-store service and UI; `useSourceDocument.ts`, `drafts.ts`.

**Accept when:**

- Version 0 and successive saves remain available across restart; restoring creates a new revision with the selected text.
- A stale second-tab restore/save fails without overwriting current source or drafts.
- Failed/interrupted writes do not expose a mismatched revision/source pair or destroy the previous saved text.
- Restore is blocked during queued/running compilation, respects dirty-source confirmation, and requires a fresh PDF build.
- Legacy jobs and history-size limits produce explicit, accurate UI states.

**Verify:** Persistence/failure-injection/conflict tests, version-limit tests, and browser save→restore→reload→build/export checks. ZIP must contain the current restored source; historical versions stay outside the normal export.

### R04 — Edit LaTeX comfortably

**Customer story:** “Help me find a section, change it, and undo a typing mistake.”

**Build:** Replace the textarea with an accessible editor supporting LaTeX highlighting, local undo/redo, find/replace, go-to-line, indentation, and a wrap toggle. Add Save and Build shortcuts with visible hints. Preserve the accessible editor name and existing local-draft recovery flow. Live automatic compilation is outside this increment.

**Implementation:** Select a maintained editor dependency after checking its license, accessibility, bundle impact, and large-document behavior at implementation time. Keep source/revision ownership in `useSourceDocument`; expose editor navigation for R05/R06. Ignore global shortcuts while a modal or IME composition is active and use platform-appropriate Ctrl/Cmd behavior.

**Likely files:** `SourceWorkspace.tsx`, `useSourceDocument.ts`, `editor.css`, `App.tsx`, `frontend/package.json`; new editor adapter.

**Accept when:**

- Find/replace and undo/redo work without losing draft recovery, conflict handling, or Unicode text.
- Go-to-line focuses and reveals the requested line; invalid positions are clamped safely.
- Save/Build shortcuts obey the same readiness and busy guards as buttons; editing remains blocked during compilation as today.
- A synthetic file near the existing 2 MB source limit remains editable; record typing/search observations on the test machine.
- Keyboard, mobile scrolling, screen-reader labeling, and Thai/CJK composition receive explicit checks.

**Verify:** Existing recovery browser tests plus focused shortcut, replacement, navigation, and input-composition checks. Do not require brittle tests for exact syntax colors.

### R05 — Turn compiler output into useful actions

**Customer story:** “Tell me why the build failed and take me to the place I can fix.”

**Build:** A diagnostics panel with error/warning severity, plain-language explanation, file/line when available, and “Go to source.” Keep raw logs available. Start with undefined commands, missing files, missing glyphs, and overfull boxes; unfamiliar messages remain visible without invented fixes.

**Implementation:** Introduce structured diagnostics tied to the compiled source revision. Parse compiler output defensively and retain unrecognized errors. Only navigate lines that belong to current `main.tex`; package-file errors must not become arbitrary local file links. If logs are truncated, expose that fact. Separate “build failed” from “built with warnings.”

**Likely files:** `DocumentInspector.java` or new compiler-log parser, `ConversionService.java`, controller, `api.ts`, `SourceWorkspace.tsx`, `ConversionReview.tsx`.

**Accept when:**

- A deliberate unknown command produces an actionable error and navigation to the appropriate source line when the compiler supplies it.
- Missing images/fonts and overfull content are categorized without claiming an automatic repair.
- Diagnostics from an older revision are labeled stale and cannot misleadingly navigate the edited revision.
- Unknown, multiline, truncated, and line-less failures retain useful raw-log access.

**Verify:** Parser fixtures plus actual Tectonic runs for a syntax error, missing asset, and warning-only build; browser diagnostic navigation and stale-state tests.

### R06 — Guide the review of risky content

**Customer story:** “Show me the table or paragraph that needs checking, then help me remember what I reviewed.”

**Build in increments:** (a) stable finding IDs and original-text excerpts, (b) location links where mappings are reliable, (c) persisted “reviewed” and “needs work” decisions scoped to source revision. Start with merged tables, numbered captions, headers/footers, and tracked changes. Provide a readable original-text panel beside source/PDF on desktop and a stacked view on mobile.

**Implementation:** Extract bounded, escaped DOCX text and structural locations using secure XML handling. This is a text/structure comparison, not a Word page renderer or a mathematical fidelity score. Expose mapping uncertainty; disable source links for ambiguous matches instead of guessing. Record review decisions separately from detected warnings. Carry an unchanged finding to a new revision only with a defined evidence fingerprint; otherwise require review again.

**Likely files:** `DocumentInspector.java`, new review-state persistence, controller, `api.ts`, `ConversionReview.tsx`, `WorkspaceView.tsx`, editor navigation from R04.

**Accept when:**

- Synthetic merged tables/captions identify useful original excerpts; essential header text is readable even when omitted from converted body text.
- A finding links to source only when a reliable match exists and says when its location is unavailable.
- Review decisions survive reload/restart and become stale when relevant source changes.
- Exported review JSON distinguishes detected risks, user decisions, and applicable revision; marking reviewed never removes the underlying warning.

**Verify:** Inspector/state-persistence tests and corpus browser walkthroughs. Include repeated captions, ambiguous text, malformed XML, and no-mapping cases.

### R07 — Fix caption numbering and explicit references

**Customer story:** “Figure 1 should be numbered once, and its references should still point to it.”

**Build in increments:** First offer a previewable repair for detected duplicate figure/table numbering. Then preserve supported explicit Word bookmark/reference relationships as LaTeX labels/references. Do not infer semantic links from arbitrary phrases such as “see Table 2.”

**Implementation:** Prefer structural conversion hooks over regex replacement across generated LaTeX. Show the exact proposed source diff before applying a repair through the normal revision-checked save path. Use stable collision-safe labels. Keep a warning for unsupported fields or ambiguous caption ownership. Add a synthetic cross-reference fixture before changing conversion behavior.

**Likely files:** `ConversionService.java`, `DocumentInspector.java`, new conversion filter under `src/main/resources/`, review UI, corpus and manifest.

**Accept when:**

- The known duplicate-caption fixture has one intended visible number after the accepted repair.
- Ordinary prose containing “Figure 1” is unchanged; cancellation leaves source untouched.
- Supported bookmark references compile to the intended figure/table, including repeated caption text and multiple figures.
- Unresolved/unsupported references are flagged; repair is recoverable through R03 and conflicts cannot overwrite newer edits.

**Verify:** Source assertions, real PDF checks for captions/references, visual inspection, and browser preview/apply/cancel/restore checks.

### R08 — Choose a usable document layout

**Customer story:** “I need the right paper size, margins, and title without editing the preamble by hand.”

**Build:** Validated A4/Letter, font-size and margin options plus title/author/date fields, on top of Article/Report. Start with generic paper/report presets. Journal/university branding is a later, separately verified template expansion.

**Implementation:** Persist structured options with backward-compatible defaults. Apply settings during conversion. For an existing edited document, generate a separate derived conversion or show a bounded preamble diff; never silently reconvert over its edits. Escape metadata and allowlist options rather than accepting arbitrary TeX via form fields. Export applied settings and compiler instructions.

**Likely files:** `Job.java`, `JobStore.java`, `ConversionService.java`, `Tools.java`, controller, `api.ts`, template UI.

**Accept when:** Existing jobs retain their layout; selected settings survive restart and affect the PDF; Unicode titles remain compilable; invalid values are rejected; applying a preset cannot overwrite edited body text; ZIP instructions match required engine/fonts.

**Verify:** Parameterized conversion/PDF tests for representative combinations and UI persistence/edit-protection cases. Visually inspect A4 and Letter output.

### R09 — Attach a bibliography and insert citations

**Customer story:** “I already have a `.bib` library. Let me use it in this project.”

**Build in increments:** (a) bounded `.bib` attachment and validation, (b) citation-key search/insertion, (c) bibliography build/export. Explicitly defer automatic reconstruction of Word citations or plain-text reference lists.

**Implementation:** Prove the bibliography toolchain against the pinned local compiler before choosing BibTeX/biblatex/citeproc integration. Do not assume a separate bibliography processor is bundled. Bind source and bibliography assets to a consistent version/export snapshot; R03 may need an asset-manifest extension. Preserve uploaded `.bib` bytes, use safe filenames, reject invalid input with useful errors, and invalidate builds when assets change. Show unknown/duplicate citation keys without inventing bibliographic metadata.

**Likely files:** conversion/export service, new bibliography asset service, controller, `api.ts`, citation UI, source editor, version manifest.

**Accept when:** A synthetic paper cites two imported keys and produces a bibliography in a real PDF; unknown/duplicate keys have actionable feedback; Unicode fields survive; ZIP recompilation needs no files outside the project; restoring a version restores its matching bibliography assets.

**Verify:** Malformed/oversize input tests, actual bibliography compilation, byte-preserving ZIP assertions, version/asset consistency tests, and browser import→insert→build workflow.

### R10 — Start the app without a developer setup

**Customer story:** “I want to install this tool and convert a document, not build its frontend.”

**Build in increments:** (a) richer setup diagnostics and recovery guidance, (b) a Windows release containing the built frontend and an appropriate Java runtime, (c) a verified installer/launcher and update instructions. Keep scripted development setup available.

**Implementation:** Choose and document packaging after checking runtime/converter/font redistribution terms. Distinguish missing tools, missing fonts, first-use TeX downloads, and genuine build failures. Keep local storage outside the install directory, preserve data during upgrades, avoid starting duplicate servers, and bind to loopback. Explain first-run network requirements before downloads; never claim fully offline installation until it is tested. macOS/Linux installers follow demonstrated demand and their own clean-machine verification.

**Likely files:** `Tools.java`, system-status API, setup/start scripts, release workflow, packaging configuration, first-run UI, README and third-party notices.

**Accept when:** A clean Windows VM with neither Java nor Node starts the released app; missing-tool/font and offline-first-build states have useful guidance; sample conversion/export works; an upgrade preserves projects, versions, and settings; uninstall makes data-retention behavior explicit.

**Verify:** Clean-machine install/launch/upgrade/uninstall checklist, diagnostic API tests, and a packaged sample conversion with multilingual font export. Do not claim other packaged platforms tested from CI build success alone.

## Deliberately deferred

| Idea | Why it waits | Revisit when |
| --- | --- | --- |
| PDF/scanned-image OCR and legacy `.doc` | New extraction engines and a much larger fidelity problem | Users repeatedly require them and DOCX repair is dependable |
| Batch conversion | Existing background workers are a foundation, but partial failure/cancellation/export UX needs a separate design | Real users regularly bring several documents at once |
| Cloud accounts, sharing, real-time collaboration | Changes the single-user privacy, storage, and worker-isolation model | There is validated demand and an explicit hosted-service design |
| AI rewrite/auto-repair chat | May change meaning and introduce external document processing | Deterministic diagnostics exist and a separate privacy/consent design is approved |
| Exact Word page-layout reproduction | Structural conversion cannot promise matching pagination | A scoped renderer/comparison approach has measured evidence |
| Direct Overleaf upload | Existing ZIP export already provides a handoff | ZIP import is demonstrated to be a frequent blocker; external integration is separately authorized |
| Plugin marketplace and broad custom templates | Large maintenance surface before core publication workflows are proven | R08/R09 usage identifies specific extensions |

## Validation and implementation rules for future agents

1. Read repository instructions, this document, the development guide, and the relevant code before editing. Inspect `git status`; preserve unrelated changes and personal documents.
2. Select one ticket and its first unfinished increment. Write down its acceptance checks and any design decision needed. Do not implement the entire roadmap in one change.
3. Keep the app local, the current upload/source/archive limits, and converter argument-array/time-limit protections unless a ticket explicitly requires a reviewed change. Hosted service work is outside this roadmap.
4. Persist backward-compatible defaults. Add restart/migration checks for changes to job metadata, versions, or review state. Never run fixture cleanup against real job storage.
5. Preserve dirty-source confirmation, draft recovery, stale-revision checks, and preview invalidation. A stale save must not become a successful overwrite.
6. Add meaningful regression tests for changed behavior. Browser mocks verify UI state handling; use real converters to establish conversion/compilation behavior. A compiling PDF alone is insufficient evidence of content fidelity.
7. Run checks appropriate to the changed files. For code releases, the repository's documented full checks are:

   ```powershell
   npm --prefix frontend run lint
   npm --prefix frontend run build
   # Install Chromium first if it is unavailable: npx playwright install chromium
   # Run the following from frontend/:
   npm run test:e2e
   # Return to the repository root:
   ./mvnw.cmd verify '-Ddoc2tex.test-pdf=true'
   ```

   On Linux/macOS use `./mvnw`. Install dependencies/tools as described in the development guide. Report missing prerequisites, skipped tests, and remaining manual verification accurately. Documentation-only changes need link/content review, not a full converter run.
8. For conversion changes, add synthetic fixtures/checkpoints, retain generated evidence outside Git, and visually inspect affected PDFs. For UI changes, check desktop/mobile and keyboard paths.
9. Update API/setup/user documentation when behavior changes. Report the ticket, files changed, checks performed, results, and remaining limitations. Mark an increment done only when its acceptance checks pass.

## Learn before extending the backlog

After each release, run the same observed workflow with representative customers using synthetic or explicitly consented local documents. Record task completion, points requiring help, time spent finding/fixing a seeded problem, and whether the exported project builds. For Release 1 also observe saved-version recovery; for Release 2 observe whether customers can find and resolve a caption/table issue.

There is no measured customer baseline yet. Establish one before claiming percentage improvements. Reprioritize R08–R10 based on observed blockers. Add findings here without collecting document contents or enabling analytics by default.

## Ready-to-use next-agent prompt

> Read repository instructions, `docs/roadmap.md`, `docs/development.md`, and the progress ledger. Continue the first ticket not marked merged, inspecting its existing code, evidence, and open PR before editing. If it is implemented, finish CodeRabbit review, address actionable findings, wait for green Windows/Linux CI on the final commit, and merge it. Only then start the next ticket in dependency order, with its own focused PR and the listed acceptance checks. Preserve draft recovery, revision conflict protection, and the local-only security model. Use isolated synthetic storage for lifecycle/deletion tests. Repeat this review, CI, and merge gate for each subsequent ticket; never begin the next before the preceding PR is merged. Keep the ledger current and report unperformed manual validation accurately.

## Progress ledger

Update the status and evidence link after each implementation; the roadmap's existence is not implementation evidence.

| Ticket | Status | Implementation / verification evidence |
| --- | --- | --- |
| R01 | Merged | [PR #7](https://github.com/braces157/doc2tex/pull/7), [implementation and verification](roadmap-r01.md) |
| R02 | Implemented; awaiting PR review and CI | [Issue #8](https://github.com/braces157/doc2tex/issues/8), [implementation and verification](roadmap-r02.md) |
| R03 | Proposed | — |
| R04 | Proposed | — |
| R05 | Proposed | — |
| R06 | Proposed | — |
| R07 | Proposed | — |
| R08 | Proposed | — |
| R09 | Proposed | — |
| R10 | Proposed | — |
