# R08 document layout

Implemented 16 October 2026 (Asia/Bangkok), after R07 was reviewed and merged in PR #19. Issue #20 tracks this change; PR review and final-head Windows/Linux CI remain required before merge.

Persist structured A4/Letter, 10/11/12 pt, 10–50 mm margins (one decimal), and bounded title/author/date fields. Legacy jobs receive Letter/10 pt/25.4 mm/empty metadata defaults without changing their source or history. Generic paper/report presets remain editable and use only allowlisted values.

Apply options during conversion. A literal Pandoc metadata filter creates Str/Space nodes rather than parsing Markdown or TeX from user text. Metadata script detection participates in the existing font export path; the metadata filter runs before the font filter. Export initial conversion settings plus compiler/font instructions, identifying that later manual source edits take precedence.

For existing projects, offer an explicit separate layout copy from the preserved original DOCX. The new conversion has its own ID/history; current source and versions are untouched. Require saved/loaded source in the UI and an expected parent revision at the API. Explain that source edits are not copied from the current project. No in-place reconversion or preamble replacement is performed.

Acceptance checks: validation/escaping, legacy migration/restart, settings through job status/rename/save/restore/deletion metadata, consistent export options/fonts, source protection on derived conversion and stale failures, parameterized actual Article/Report PDFs with A4/Letter geometry and representative fonts/margins, Unicode metadata with no missing glyphs, visual PDF inspection, and browser upload/preset/persistence/dirty/conflict/copy workflows on desktop/mobile. All jobs/test evidence must use synthetic isolated storage.

## Verification

- Frontend lint and production build pass without warnings. Layout controls are lazy-loaded (about 4.04 kB); the initial app chunk remains about 496.22 kB with no bundle warnings.
- Full Maven `verify -Ddoc2tex.test-pdf=true` passes 89 tests, zero skipped. Six layout cases verify validation, unchanged legacy source/history, settings after restart/save/restore/rename, parent revision protection, separate project/history, original DOCX bytes, exported settings/compiler instructions and metadata-only Noto assets. All six pass again after the empty-author metadata cleanup.
- Four actual PDF combinations cover Article/A4/11 pt/20 mm, Report/Letter/12 pt/30 mm, Article/Letter/10 pt/25.4 mm and Report/A4/11 pt/15 mm. Assertions check every page's dimensions, body text position/font size, literal TeX-like metadata, supported Thai/Chinese titles, title/author text and absence of missing-glyph messages. Poppler renders of all six pages were inspected: readable metadata/body/tables, expected report title pages, no clipping or overlap. Evidence: `target/fidelity-evidence/layouts/`.
- HTTP checks exercise multipart layout JSON, separate-copy JSON, invalid/fractional font sizes, stale/missing parent revisions, preserved edited parent source/history and rejection before creating an invalid upload job. Numeric inputs are validated without silently truncating fractional font sizes.
- All 104 desktop/mobile browser workflows pass, including six new layout cases: generic preset, metadata/margin edits, multipart options, settings restored after reload, dirty-source protection, cancel, separate-copy creation, reopening the edited parent, invalid margins and stale-copy errors. Desktop/mobile option screenshots were inspected with no page overflow.

Settings are immutable per conversion; restoring source does not select different conversion settings. The layout-copy option intentionally starts from Word, so manual LaTeX body edits are retained only in the parent project. Broad custom templates, in-place preamble rewriting and unsupported-script font expansion remain outside this ticket. No live Word renderer, OS screen-reader/IME session or customer usability study was performed. Test storage and evidence are synthetic; real `.data` documents were untouched.

CodeRabbit requested two recovery refinements. Invalid stored layout values now log a warning and fall back to legacy defaults while API validation stays strict. Restart tests for both nonnumeric font size and unsupported paper verify the library entry, saved source/history and original download remain available; interrupted deletion markers with the same damage can restart and finish cleanup. Full backend/PDF verification passes 91 tests, zero skipped.

New conversion also clears layout/title/author/date instead of carrying metadata from the previous document. Desktop/mobile upload checks now reset after reopening a saved project, verify all cleared/default fields and inspect a subsequent upload's default metadata. Lint/build and all 104 browser workflows pass again after these review refinements.
