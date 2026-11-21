# Docs & PDF Ecosystem Roadmap

Created: 21 November 2026. Builds directly on the completed R01–R10 DOCX→LaTeX baseline in [the core product roadmap](roadmap.md).

## Product positioning

**Unified Local Document & PDF Studio.** Keep `DOCX → LaTeX` as the flagship precision publishing workflow while adding a clean sidebar mode switcher for local PDF manipulation, bidirectional format conversion, PDF-to-editable extraction, document comparison, and academic/batch workflows.

Every tool adheres to the same local-first guarantee: files stay on the user's machine (or isolated demo session when opt-in demo mode is enabled), outputs remain deterministic and inspectable, and no external cloud API or telemetry participates.

## Existing engine reuse inventory

`doc2TeX` already ships the core local engines required across the entire ecosystem:

| Engine already in repo | Baseline role (R01–R10) | Ecosystem leverage (E01–E05) |
|---|---|---|
| **Apache PDFBox 3.0.8** (`pom.xml`) | PDF text-coverage verification & page rasterization | PDF inspection, merge/split/reorder/rotate, metadata/XMP & annotation scrubbing, watermarks, page numbering, text/image extraction, visual/text PDF diff |
| **Pandoc 3.12.1** (`.tools/pandoc`, `--sandbox`) | `DOCX → LaTeX` | Bidirectional conversion across `.md`, `.html`, `.epub`, `.odt`, `.rtf`, `.tex`, `.typ`, `.pptx`/Beamer, plus `LaTeX → DOCX` round-trip |
| **ONNX Runtime 1.32 + PP-DocLayout-S** | DOCX unstyled heading & 2-column detection | Region-aware digital `PDF → Markdown / LaTeX / DOCX` (titles, abstracts, reading order, tables, figures, formulas, headers/footers, references) |
| **LibreOffice Headless / Docker** (`SourcePageRenderer.java`) | Rendering DOCX to PDF for AI layout analysis | Legacy `.doc`/`.odt`/`.rtf`/`.pptx` normalization and Word-vs-compiled-LaTeX visual PDF comparison |
| **Tectonic 0.17.0 + PDF.js 6.5 + CodeMirror 6** | LaTeX compile, preview, and source editing | Expanded academic template studio, visual thumbnail page grid, side-by-side diff viewer |

## Architectural rules

1. **Modularity (<600 prod LOC per file):** Keep [ConversionService.java](../src/main/java/dev/doc2tex/ConversionService.java) isolated to DOCX conversion and job lifecycle. Implement each ecosystem slice in dedicated deep services and controllers (e.g., `PdfStudioService`, `PdfStudioController`, `MultiFormatService`, `PdfExtractService`, `CompareService`).
2. **Security & Demo Isolation:** Every new HTTP route sits behind [LocalAccessFilter](../src/main/java/dev/doc2tex/LocalAccessFilter.java) (loopback host/origin & cross-site enforcement) and [DemoGuardFilter](../src/main/java/dev/doc2tex/DemoGuardFilter.java) (IP rate limits and session isolation when `demoMode` is active).
3. **Strict Resource Bounds:** Enforce per-file size caps, combined request limits, page count caps, character/image extraction budgets, cycle-safe PDF resource traversal, and strict filename/path sanitization. Never execute external converters without argument arrays, timeouts, and `--sandbox` / `--untrusted` flags.
4. **No Telemetry:** Never collect or upload document contents, filenames, or usage analytics.
5. **UI Consistency (`DESIGN.md`):** Follow [the design system](../DESIGN.md) tokens (`#f5f3ee` warm canvas, `#1f4d3a` forest accent, `#ffffff` cards, `tabular-nums`, zero gradients) and verify all 6 edge states (Empty, Loading, Overflow/Density, Error/Offline, Keyboard/Focus, Contrast) on desktop and mobile.

---

## Phased slices

### E01 — Local PDF Studio *(Slice 1 — Zero new dependencies)*

**Customer outcome:** Inspect, reorganize, merge, split, clean, watermark, number, and extract content from local PDFs (or compiled workspace PDFs) without uploading sensitive documents to third-party web tools.

- **Modules:** `PdfStudioService.java`, `PdfStudioModels.java`, `PdfStudioController.java`, `frontend/src/PdfStudio.tsx`, `frontend/src/PdfThumbnails.tsx`, `frontend/src/pdfApi.ts`, `frontend/src/pdfWorker.ts`, `frontend/src/pdf-studio.css`, plus workspace sidebar switcher in `App.tsx` / `WorkspaceView.tsx`.
- **API routes (`/api/pdf/*`):**
  - `POST /api/pdf/inspect` — Inspect PDF version, encryption state, page dimensions/rotations/char & image counts, outline bookmarks, standard metadata + XMP presence, and actionable warnings (scanned/no-text, metadata present, annotations present).
  - `POST /api/pdf/assemble` — Merge up to 20 PDFs, select/split/reorder/delete/rotate pages (up to 500 output pages), scrub document info + XMP metadata + trailer ID, strip annotations, stamp diagonal watermarks (with opacity & Unicode Noto fallback), and stamp footer page numbers (`1 / N`).
  - `POST /api/pdf/extract/text` — Bounded per-page plain text extraction (`2,000,000` char cap).
  - `POST /api/pdf/extract/markdown` — Structured page-sectioned Markdown extraction.
  - `POST /api/pdf/extract/images` — Cycle-safe, deduplicated embedded raster image extraction packaged as PNGs + `manifest.json` inside a ZIP (`200` images / `80 MB` cap).
- **Acceptance gate:** All 5 endpoints verified via `@SpringBootTest` integration tests with programmatic PDFBox 3.0.8 fixtures, demo rate limiting verified in `HostedDemoModeTest`, and Playwright E2E coverage across desktop & mobile (`390px`) with zero horizontal overflow and full preservation of unsaved LaTeX editor drafts when switching modes.

---

### E02 — Universal Multi-Format Hub *(Pandoc 3.12 + optional LibreOffice)*

**Customer outcome:** Convert freely across documents, notes, e-books, and slides — including the `LaTeX → DOCX` collaborator round-trip — inside one unified local studio.

- **Depends on:** Existing `Tools` (Pandoc 3.12.1), `SourcePageRenderer` (optional `.doc` normalization).
- **Planned modules:** `MultiFormatService.java`, `MultiFormatController.java`, `frontend/src/FormatHub.tsx`.
- **Capabilities:**
  - **Expanded inputs:** `.md`, `.html`, `.epub`, `.odt`, `.rtf`, `.tex`, and legacy `.doc` (normalized via headless LibreOffice profile when configured).
  - **Multi-format exports:** Export any converted or edited workspace project to `.docx` (preserving citations/figures where Pandoc supports them), LLM/docs-ready Markdown, self-contained HTML, EPUB, Typst (`.typ`), or Beamer / `.pptx` slides.
  - **Round-trip preview & warnings:** Report unsupported macros or degraded elements honestly before download.
- **Acceptance gate:** Parameterized Pandoc `--sandbox` round-trip tests (`DOCX ↔ LaTeX`, `MD ↔ DOCX/HTML/EPUB/Typst`) with media preservation and path-traversal checks.

---

### E03 — PDF → Editable Documents *(PDFBox + PP-DocLayout-S ONNX)*

**Customer outcome:** Turn digital PDFs (research papers, reports, handouts) into editable Markdown, LaTeX, or DOCX drafts in the existing CodeMirror workspace.

- **Depends on:** `E01` PDF extraction, `DocLayoutModel` (`PP-DocLayout-S` ONNX), `Tools` (Pandoc).
- **Planned modules:** `PdfToSourceService.java`, `PdfLayoutReconstruction.java`, UI handoff from `PdfStudio` into workspace editor.
- **Capabilities:**
  - **Phase 3a (Digital PDFs):** Combine `PDFBox` character coordinates/styles/images with `PP-DocLayout-S` bounding-box classification (`doc_title`, `paragraph_title`, `abstract`, `text`, `table`, `figure`, `formula`, `header`, `footer`, `reference`) to strip running headers/footers, resolve multi-column reading order, crop figures/tables as assets, and emit editable Markdown or LaTeX (`TeX` / `DOCX` via Pandoc).
  - **Phase 3b (Optional Scanned OCR & Formula Pack):** Opt-in checksum-verified local ONNX OCR/formula model following the `setup-ai-layout` script pattern, keeping core installation lightweight.
- **Acceptance gate:** Multi-column academic PDF fixture converts with preserved reading order, zero running-header bleed, cropped figures linked in output ZIP, and compiling LaTeX output.

---

### E04 — Visual & Text Diff + DOCX Pre-Flight Cleaner

**Customer outcome:** Verify changes between document revisions visually and clean bloated Word drafts before sharing or archiving.

- **Depends on:** `E01` (`PDFBox` raster/text primitives), `R03` (`SourceVersionStore`), `SourcePageRenderer`.
- **Planned modules:** `CompareService.java`, `DocxCleanerService.java`, `frontend/src/DiffStudio.tsx`, inline diff view inside `VersionHistory.tsx`.
- **Capabilities:**
  - **Visual & Text PDF Diff:** Side-by-side and pixel-highlight diff between any two PDFs, saved workspace revisions, or Word-rendered PDF vs compiled LaTeX PDF.
  - **Source Version Diff:** Line-level unified/split diff inside [VersionHistory.tsx](../frontend/src/VersionHistory.tsx) before restoring an older snapshot.
  - **DOCX Pre-Flight Cleaner:** Inspect embedded media bloat, compress oversized images inside `.docx`, strip comments/tracked-change author metadata, and audit missing fonts or broken hyperlinks.
- **Acceptance gate:** Pixel/text diff detects intentional 1-line and layout shifts without false positives on identical renders; DOCX cleaner preserves valid OOXML schema (`DocxValidator`) across all corpus fixtures.

---

### E05 — Academic & Batch Suite

**Customer outcome:** Format papers for common venues, process folders of documents at once, and extract citations directly into `.bib`.

- **Depends on:** `E01`–`E03`, `R08` (`LayoutOptions`), `R09` (`BibtexLibrary`, `BibliographyPanel`).
- **Planned modules:** `TemplateCatalog.java`, `BatchJobService.java`, `ReferenceExtractor.java`, `frontend/src/BatchWorkspace.tsx`.
- **Capabilities:**
  - **Template Studio:** Verified open-license layouts beyond `Article`/`Report` (two-column IEEE-style conference paper, ACM-style, Beamer slides, Thesis/Book, clean CV) tested against pinned Tectonic.
  - **DOI & Reference Extractor:** Scan DOCX/PDF reference sections for DOIs, ISBNs, arXiv IDs, and structured citations to generate clean `.bib` drafts wired into [BibliographyPanel.tsx](../frontend/src/BibliographyPanel.tsx) (offline parsing first; optional explicit lookup).
  - **Batch Queue & CLI/Watch-Folder:** Queue multiple DOCX/PDF files for conversion, merge, or multi-format export into a single structured ZIP with per-file error isolation.
- **Acceptance gate:** All new templates compile cleanly under Tectonic with zero missing-glyph warnings; batch queue isolates single corrupt files without failing healthy siblings.

---

## Progress ledger

| Slice | Status | Implementation / verification evidence |
|---|---|---|
| **E01 — Local PDF Studio** | Implemented & verified | Backend (`PdfStudioModels`, `PdfStudioService`, `PdfStudioExtractor`, `PdfStudioStamper`, `PdfStudioController`), Frontend (`PdfStudio`, `PdfLoadedStrip`, `PdfOrganizeTab`, `PdfInspectTab`, `PdfExtractTab`, `PdfThumbnails`, `pdfApi`, `pdfWorker`), `PdfStudioTest`, `HostedDemoModeTest`, and `pdf-studio.spec.ts` |
| **E02 — Universal Multi-Format Hub** | Implemented & verified | Backend (`MultiFormatService`, `MultiFormatController`, `SourcePageRenderer`), Frontend (`FormatHub`, `MultiFormatTab`, `WorkspaceView` export menu, `ecosystemApi`), `MultiFormatTest`, `HostedDemoModeTest`, and `ecosystem.spec.ts` |
| **E03 — PDF → Editable Documents** | Implemented & verified | Backend (`PdfToSourceService`, `PdfStudioController`, `AiLayoutService`), Frontend (`PdfToSourceTab`, `PdfStudio`, `ecosystemApi`), `PdfToSourceTest`, and `ecosystem.spec.ts` |
| **E04 — Visual & Text Diff + DOCX Cleaner** | Implemented & verified | Backend (`PdfDiffService`, `DocxCleanerService`, `CompareAndCleanController`), Frontend (`CompareStudio`, `PdfDiffTab`, `DocxCleanerTab`, `VersionHistory`, `textDiff`, `ecosystemApi`), `CompareAndCleanTest`, and `ecosystem.spec.ts` |
| **E05 — Academic & Batch Suite** | Implemented & verified | Backend (`AcademicTemplateService`, `ReferenceExtractorService`, `BatchService`, `EcosystemToolsController`), Frontend (`TemplatesAndRefsTab`, `BatchConvertTab`, `FormatHub`, `ecosystemApi`), `EcosystemToolsTest`, and `ecosystem.spec.ts` |
