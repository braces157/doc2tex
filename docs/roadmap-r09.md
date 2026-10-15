# R09 bibliography attachment and citations

Implemented 16 October 2026 (Asia/Bangkok), after R08 was reviewed and merged in PR #21. Issue #22 tracks this work. PR review and final-head CI remain the merge gate.

The pinned Tectonic 0.17.0 executable was tested before choosing integration. A synthetic source with two entries compiled with `PATH` empty, produced `main.bbl` and a PDF, and retained accented/Cyrillic titles. The log explicitly ran BibTeX and reran TeX. Evidence: `target/r09-bibtex-probe/`. This agrees with the [official Tectonic documentation](https://tectonic-typesetting.github.io/en-US/index.html), which describes automatic TeX/BibTeX passes. No separate BibTeX/Biber executable is assumed.

Implement one bounded UTF-8 BibTeX attachment per project, preserved byte-for-byte under a fixed safe project path. Validate structure and citation keys without inventing bibliographic metadata. Duplicate/unknown keys require actionable feedback. Keep Word citation reconstruction and plain-text bibliography reconstruction deferred.

Extend immutable R03 snapshots to bind source and bibliography assets to the same committed revision. Source saves carry current assets; restores carry the selected version's assets; build caches recover from that committed snapshot; ZIP exports read canonical assets. Asset changes invalidate previews and count toward history limits. Preserve unmanaged local files when preparing the bibliography cache, and retain recovery behavior for interrupted writes or corrupt snapshots.

Offer citation-key search and editor insertion through CodeMirror transactions, preserving dirty text/undo/draft ownership. Add an explicit previewable source setup for a standard plain BibTeX bibliography when the document structure can be verified. Preserve uploaded bytes, standard portable relative paths and compiler/font instructions. Supported Unicode font handling must be verified in real PDFs; unusual scripts/custom bibliography setups remain explicit manual-review cases.

Acceptance checks include malformed/oversize/duplicate/unknown keys, UTF-8 bytes, real two-key citation/bibliography compilation, source/asset version restoration, corruption and interrupted commits, ZIP byte preservation and clean-directory recompilation, plus desktop/mobile import→insert→save→build and conflict/draft protection workflows. Use only synthetic isolated storage.

## Implementation

- Added bounded structural BibTeX validation, searchable literal metadata and actionable duplicate/unknown-key feedback. Limits cover file bytes, entries, nesting, fields, concatenations and aggregate macro expansion. Original UTF-8 bytes remain unchanged.
- Extended immutable version manifests with bibliography/font/license byte counts and SHA-256 digests. Imports, source saves, restores, downloads, compilation and ZIP export use the same committed revision. Owned cache files recover after interrupted commits; unmanaged files and legacy source-only snapshots remain protected.
- Added a lazy bibliography panel, CodeMirror insertion transactions and an exact source setup preview. Imports/setup enforce clean saved source and revision guards; insertion preserves edits and undo. Loading, retries, busy imports, stale responses, history downloads and restore failures keep source/drafts available.
- Standard setup uses the compiler's embedded BibTeX and portable `bibliography/` paths. Captured Noto Serif and its license support accented Latin, Greek and Cyrillic bibliography text independently of body font selection. Replacements carry the captured font. Other scripts/custom packages remain manual-review cases. No Word or plain-text citation reconstruction was added.

## Verification

- Frontend lint and production build pass; the initial bundle stays below Vite's 500 kB warning threshold (498.45 kB), with bibliography UI loaded separately.
- The full 114-check desktop/mobile suite passed before review fixes. All twelve bibliography checks pass after review, including composition-blocked insertion feedback/retry, import, exact setup cancel/apply, selection insertion/undo, search, save/build, replacement, matching restore/download, download retry, pending-import edit protection and stale responses. Final-head CI reruns the complete suite.
- The full 108-test backend/PDF suite passed with zero skipped before review fixes; all seven parser/API/PDF checks pass afterward. Targeted bibliography checks cover malformed/oversize/duplicate/unknown input, UTF-8, history limits, three interrupted-commit checkpoints, corrupt assets, owned/unmanaged caches, captured-font retention and historical downloads.
- Article and Report fixtures each cite two imported keys and compile real PDFs with accented and Cyrillic bibliography titles. All three resulting pages were rendered and visually inspected; text extraction and compiler-log checks pass. Exported source, bibliography bytes, font and license are checked, and the ZIP recompiles in an isolated directory without project-external assets. TeX packages may still come from the normal Tectonic cache/network.
- Desktop/mobile key search, source diff and saved-version dialogs were visually inspected. A mobile history overlap found during this check was fixed; the complete browser suite passed afterward.

Evidence is generated under ignored `target/r09-*`, `target/fidelity-evidence/bibliography/` and `frontend/test-results/`. Fixtures use isolated synthetic storage. Real user project storage was not used.

Code review identified percent-comment handling that differed from classic BibTeX. The pinned compiler reproduced a repeated-entry error for `% @book{duplicate,...}` followed by another definition (`target/r09-percent-probe/main.blg`). The parser now discovers that entry, rejects the duplicate and rejects percent text used as whitespace inside entries. Literal percent characters in field values are preserved. A skipped citation insertion now explains the reason and supports retry; synthetic busy-state cleanup also runs in `finally`.

The integration intentionally supports one attachment and a documented ASCII citation-key subset. It validates structure rather than certifying bibliographic correctness or every TeX macro. No clean-machine/offline package availability claim is made here; packaging and setup diagnostics belong to R10.
