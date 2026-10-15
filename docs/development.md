# Development and API reference

Return to the [project overview](../README.md).

A local DOCX → LaTeX app built with **Spring Boot 4.1.1, Java 21+, React, TypeScript, shadcn/ui, Vite, Pandoc, and Tectonic**.

## What works

- DOCX upload and drag and drop, up to 20 MB.
- Article and report templates.
- A4/Letter layout options, bounded font sizes/margins, literal title/author/date metadata, and separate layout copies that preserve edited projects.
- Background conversion with two workers and a bounded queue.
- Editable LaTeX source, save, copy, and `.tex` download.
- Browser-local draft recovery, active-document restoration after reload, and retryable source loading.
- ZIP export with `main.tex`, extracted images, and conversion messages.
- PDF compilation and a PDF.js canvas viewer with page navigation and PDF download, requested separately from conversion.
- Local conversion history that survives application restarts.
- All documents library with filename search, status filters, creation-date sorting, and pagination across every saved conversion.
- A built-in sample with a heading, emphasis, native Word math, a table, and an image.
- Validation, inline errors, progress states, and a responsive interface.
- Keyboard-accessible shadcn tabs, radio controls, tooltips, dialogs, toasts, and download menus.
- Conversion review with source feature counts, layout risks, tracked-change handling, and compiler warnings.
- Conditional Noto font bundling for tested multilingual scripts, including Thai, Chinese, Japanese, Arabic, Greek, and Cyrillic.

## Run on Windows

Prerequisites: JDK 21 or newer and Node.js 24 LTS (recommended). The Maven Wrapper downloads Maven 3.10.0; a system Maven installation is optional. Run commands from this project directory. Replace `./mvnw.cmd` with `./mvnw` on Linux/macOS.

```powershell
# Download project-local, checksum-verified converters and multilingual fonts.
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/setup-tools.ps1

# Install frontend dependencies, build the interface, and start Spring Boot.
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start.ps1
```

Open [http://127.0.0.1:8080](http://127.0.0.1:8080). Stop the foreground server with Ctrl+C.

Tools live in `.tools/`; no system PATH change is needed. Use `-SkipPreview` on the setup script if only LaTeX conversion is needed. Tectonic downloads and caches TeX packages on its first PDF build, which needs internet access and can take longer than later builds. Conversion and ZIP export work independently of PDF compilation.

Noto fonts are downloaded from pinned upstream commits with verified checksums. Only fonts needed by a multilingual document are included in its ZIP, along with their OFL licenses. If the export includes `fonts/`, compile it with **XeLaTeX** in Overleaf or your editor. To add fonts to an existing installation, run `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/setup-fonts.ps1`. `-SkipFonts` skips font setup when running `setup-tools.ps1`; conversion still preserves Unicode source, but previews can lack glyphs. Other scripts and unusual mixed-direction text may need manual font or language settings.

On Linux x86-64 and macOS (Intel/Apple Silicon), run `bash scripts/setup-tools.sh`, then `bash scripts/start.sh`. These scripts use curl, tar, unzip, and SHA-256 tools. Other architectures can install Pandoc and Tectonic manually. The app discovers project-local executables, then PATH, or uses `PANDOC_PATH` and `TECTONIC_PATH`.

## Development

Run these in two terminals:

```powershell
# Terminal 1, project root
./mvnw.cmd spring-boot:run

# Terminal 2
cd frontend
npm ci
npm run dev
```

Open [http://127.0.0.1:5173](http://127.0.0.1:5173). Vite proxies `/api` to Spring Boot on port 8080. Production frontend files are served by Spring Boot after a frontend build and a Maven build/restart.

## Verify and package

```powershell
cd frontend
npm run build
npm run lint
cd ..
./mvnw.cmd test
./mvnw.cmd package
java -jar target/doc2tex-0.1.0.jar
```

Windows locks the executable JAR while it runs. Stop that server before packaging, or use `./mvnw.cmd verify '-Ddoc2tex.build-directory=target/verification'` to build into a separate directory.

Tests cover input validation, archive traversal, persistence/restart recovery, actual Pandoc conversion of math/tables/images, article/report output, source edits, revision conflicts, and ZIP contents. Real conversion tests are skipped if Pandoc is not installed. PDF compilation is deliberately outside the default tests because its first run downloads TeX dependencies.

The full PDF suite compiles every corpus fixture with both Article and Report. Test-only PDFBox extraction checks explicit `pdfMustContain`/`pdfMustNotContain` checkpoints in `manifest.json`, minimum page counts, and the long register's final-page checkpoint. The entire compiler log is checked for missing glyphs. ZIP checks verify saved source, review JSON, referenced images and Noto fonts, licenses, and byte-for-byte preservation of extracted media/font assets. These are specific content checks, not a guarantee of visual fidelity or correct extraction of every Unicode script.

Generated PDFs, extracted text, and complete compiler logs are written to `target/fidelity-evidence/<template>/<fixture>/` (under the selected build directory when overridden). Windows/Linux CI uploads them as `pdf-fidelity-*` artifacts, including evidence produced before an assertion fails.

Browser workflow tests use Playwright with Chromium at desktop and mobile widths. They start their own Vite server on port 4173 and mock API responses to exercise recovery, revision conflicts, storage failures, and failed uploads deterministically; real converter coverage remains in the backend suite. Run them with:

```powershell
cd frontend
npx playwright install chromium
npm run test:e2e
```

CI runs these tests on Windows and Linux and retains traces/screenshots on failure.

Unsaved edits are copied to this browser's local storage on each edit, keyed by document ID and saved revision. Reloading the same tab reopens its active document; opening the document from history in a new tab also offers any available draft. Choose **Restore draft**, **Download draft**, or **Discard draft** before editing. A draft from a different saved revision can be downloaded for comparison, but cannot directly replace the current source. Successful saves and explicitly discarded edits remove this window's draft; they do not remove a newer copy written by another window. Drafts remain in this browser until saved/discarded or browser storage is cleared, and are separate from the server's `.data/jobs` storage. Private browsing, browser cleanup, or storage limits can make recovery unavailable; an inline message explains when saving a local draft fails.

Failed source requests show **Retry loading source** and block editing, PDF builds, and exports until source loading succeeds. A failed replacement upload keeps the existing source and draft available.

The hard-document corpus adds merged tables, fractions, radicals, a matrix, footnotes, nested lists, images, links, accepted revisions, header/footer warnings, eight language samples, and a ten-page register with a 90-row table. Enable PDF regression checks after tool and font setup:

```powershell
./mvnw.cmd test '-Ddoc2tex.test-pdf=true'
```

To repeat the HTTP checks and render page images, use Python with `pypdf` and Pillow plus Poppler on PATH:

```powershell
python scripts/check-corpus.py
```

This writes measurements, extracted text, reviews, PDFs, and contact sheets to `.data/corpus-results`. The deterministic source fixtures and manifest live in `src/test/resources/corpus`; regenerate with `scripts/create-corpus.py` using Python with `python-docx` and Pillow. See [the test report](conversion-test-results.md) for observed results and limits.

## API

| Method | Route | Purpose |
|---|---|---|
| GET | `/api/system` | Tool availability, templates, upload limit |
| GET | `/api/sample.docx` | Download the sample |
| POST | `/api/jobs` | Multipart `file`, `template` and optional JSON-string `layout`; returns 202 and a job |
| POST | `/api/jobs/{id}/layout-copy` | JSON `{ "revision": 1, "template": "report", "layout": {...} }`; creates a separate conversion from the original DOCX, returns 202 and its new job |
| GET | `/api/jobs` | Most recent 30 jobs |
| GET | `/api/library` | All stored jobs, filtered and paginated (see below) |
| GET | `/api/jobs/{id}` | Conversion and preview status |
| PATCH | `/api/jobs/{id}` | JSON `{ "displayName": "Paper draft" }`; rename without changing the original filename |
| DELETE | `/api/jobs/{id}` | Permanently remove this document's original, source, versions, media and previews; 204 on success |
| GET | `/api/jobs/{id}/review` | Detected source features, review flags, and PDF compiler warnings |
| GET | `/api/jobs/{id}/caption-repairs` | Read-only caption proposals with exact before/after source, revision, SHA-256 source hash and manual-review warnings |
| POST | `/api/jobs/{id}/caption-repairs` | JSON `{ "revision": 0, "sourceHash": "...", "proposalIds": ["..."] }`; applies selected current proposals as a normal saved version and returns `{job, source}` |
| GET | `/api/jobs/{id}/source` | UTF-8 LaTeX source |
| GET | `/api/jobs/{id}/original` | Download the original DOCX, including when saved history cannot be recovered |
| PUT | `/api/jobs/{id}/source` | JSON `{ "source": "...", "revision": 0 }`; saves and invalidates the PDF |
| GET | `/api/jobs/{id}/versions` | Saved source history, current/baseline revision, migration state and storage limits |
| GET | `/api/jobs/{id}/versions/{revision}/source` | Immutable UTF-8 text of one saved version |
| POST | `/api/jobs/{id}/versions/{version}/restore` | JSON `{ "revision": 2 }` (required current revision); returns `{job, source}` for the new restored revision |
| GET | `/api/jobs/{id}/download` | ZIP of saved LaTeX, images, and README |
| POST | `/api/jobs/{id}/preview` | Queue PDF compilation |
| GET | `/api/jobs/{id}/preview` | Current compiled PDF |
| GET | `/api/jobs/{id}/preview/log` | Compiler output, capped at 64 KB |

Conversions: `QUEUED → CONVERTING → COMPLETED / FAILED`.
Previews: `NOT_REQUESTED → QUEUED → COMPILING → READY / FAILED`.

**All documents:** `GET /api/library?query=&status=&sort=newest&page=0&size=20` returns `{items, page, size, total}`. Original filename and display-name matching is case-insensitive and trims surrounding query whitespace; filtering happens over the whole stored collection before pagination. `query` is limited to 200 characters. `status` is empty (all), `QUEUED`, `CONVERTING`, `COMPLETED`, or `FAILED`; `sort` is `newest` or `oldest`. Pages are zero-based, size is 1–100, and invalid parameters return 400. Creation-date ties sort by ascending job ID for both directions. Pages beyond the end return empty items with the actual total. The recent-history endpoint stays capped at 30. The workspace restores the active conversion by ID even when it is absent from recent history. Library selection uses the same unsaved-edit confirmation and draft recovery as recent documents.

**Rename and delete:** Library rows expose Rename and Delete actions. `displayName` is optional persisted metadata (empty for legacy jobs); a blank name restores the original filename as the UI title. Names are trimmed, limited to 200 characters, and reject control characters. Renaming preserves source, revision, preview, job ID, and original filename. Downloads keep the original filename.

Deletion requires confirmation naming the document. It rejects queued/running conversion or PDF compilation and active source/save/review/log/PDF/ZIP operations with 409. Per-job read/write leases cover the complete file operation, including streaming downloads. Storage must be a normal local directory: UUID validation and no-follow checks reject symlinks and Windows reparse points, including linked descendants, before cleanup. A second filesystem process is outside the single-user worker-isolation model; this is not an OS sandbox.

Workers keep a shared project guard throughout tool execution and final status persistence. Source/log/review readers can proceed during compilation; mutations and deletion cannot interleave with worker output or mark a newer revision READY. HTTP file operations and metadata/source edits acquire leases without waiting and return 409 when incompatible work holds the document. Worker status snapshots use the store's short synchronized, atomic metadata write while retaining the guard, with no unlock/relock gap around final status changes.

Before cleanup, a durable metadata copy is atomically written to `.deletions/{id}.properties` inside the configured data directory. If cleanup fails, the API returns 503 and listing entries include `deletionPending: true`; opening or editing that document returns 410. Retry Delete from All documents after correcting filesystem permissions or releasing external file locks. Cleanup can already have removed some files and cannot be undone. Restart retains the retry entry even if the UUID directory is already gone. Success removes both job files and marker before removing the listing entry. An unreadable deletion marker stops startup to avoid exposing a partially removed project: back up the data directory and repair the marker before restarting; do not remove a marker while its partial UUID directory remains.

Matching browser draft keys and this tab's active-job key are removed only after a successful delete response. Other tabs confirm the deletion on a browser storage notice or window focus; missing-document responses also disable save/build/export. Loaded text stays in memory with **Download unsaved text**, and any local recovery draft has **Download draft**. Download before leaving or reloading that tab. With browser storage disabled, a tab learns about deletion on focus or its next server request.

Saving uses a revision check to prevent overwriting edits from another window. Saving is blocked while that job's preview is queued/compiling. Downloads include the latest saved source; the interface saves pending edits before exporting a ZIP or building a PDF.

**Document layout:** Expand Layout options beside Article/Report. Generic paper selects Article, A4, 11 pt and 25 mm margins; Generic report selects Report, Letter, 12 pt and 25.4 mm margins. You can adjust these values and add a title, author and date before converting. Empty date text omits the date. Allowed paper sizes are `a4` and `letter`, font sizes are exactly 10/11/12 pt, and margins are 10–50 mm with at most one decimal. Title/author allow 200 characters each and date 80; control characters are rejected. Metadata is literal text, so Markdown and TeX commands are escaped rather than executed.

The `layout` JSON object has `{ "paper": "a4", "fontSize": 11, "marginMm": 25, "title": "Paper title", "author": "Jane Doe", "date": "October 2026" }`. Jobs return these persisted settings and retain them across restart, rename, save and restore. Omitting upload options preserves the legacy Letter/10 pt/one-inch defaults. Existing projects keep their saved source and history; migration adds metadata defaults only.

For an existing document, save pending source edits, choose options and select **Create layout copy**. The confirmation explains that the new project starts from the preserved original DOCX; edited LaTeX remains in the current project and is not carried into the copy. The parent revision is checked, a new job ID/version-0 history is created, and the parent source/versions remain unchanged. Cancel or a failed request keeps the current editor. Changing form options alone does not change saved source or job settings. To preserve manual body edits within a layout, edit the preamble manually in the existing source.

Exports include `layout-settings.json` describing the initial conversion and README compiler instructions; later manual source changes take precedence. Title/author/date script detection participates in conditional Noto font bundling, even when the original body is English. Review flags multilingual metadata and compiler missing-glyph warnings remain available; unsupported scripts may require manual font settings. Compile with Tectonic or current XeLaTeX, keeping the exported media/fonts and licenses beside `main.tex`. The layout conversion filter itself is not needed for compilation.

**Saved versions:** Open Saved versions beside Save changes to preview, download or restore earlier saves. New conversions keep the original generated source as version 0. Existing projects begin with a snapshot of their current saved revision; the dialog identifies that earlier overwritten history is unavailable. Restore checks the expected current revision, creates a new increasing revision, preserves earlier snapshots, invalidates the PDF and returns its matching source and metadata together. Unsaved changes require confirmation and are only discarded after a successful restore; failed/stale restores preserve local text and recovery drafts. Restore is blocked while PDF compilation is queued/running. Build a fresh PDF afterward.

**Caption repairs and explicit references:** In Review, choose **Preview caption repairs** after saving edits. Inspect each exact source diff, deselect any unwanted proposals, then apply. Cancel leaves source untouched. Applying creates a normal saved version; use Saved versions to restore the original generated captions. Stale revision/hash/proposal checks return 409 without overwriting text, and compilation blocks repairs. Previewing does not build a PDF.

New conversions preserve supported numeric Word body REF fields attached to unique bookmarks inside actual figure/table captions as LaTeX labels and references. Repeated caption text does not establish ownership. Ordinary prose and ordinary hyperlinks retain their meaning; the app never infers references from phrases such as “see Figure 1.” PAGEREF, NOTEREF, nested/custom fields, missing or duplicated bookmarks, detached captions and fields in headers/footers/notes require manual comparison. Warnings remain in Review and the exported conversion review. Legacy conversions keep their saved source and show a field-verification warning; reconverting the original creates a separate document.

Automatic numbering proposals cover recognized English literal prefixes inside verified numbered captions. Starred/custom/optional short captions and unbalanced source require manual review. The generated identity macro preserves the original visible prefix until a repair is accepted; its definition is included in standalone source/ZIP exports. The structural conversion filter is not needed to compile the exported project.

History retains up to **100 versions or 100 MiB of saved source per document**, including the baseline. Saves/restores beyond either limit return 507 with a storage-limit explanation. No protected history is silently pruned. Download the current source and export the project before starting a new document when history is full. Normal ZIPs contain only the committed current source, media, fonts and review; historical versions are available as individual source downloads. Deleting a document removes its history too.

Source snapshots and their byte counts/SHA-256 digests are stored outside exports in `.versions/{revision}/`. `job.properties` revision is the committed pointer. Saves atomically publish an immutable snapshot, replace the working source cache, then atomically advance job metadata under the exclusive lease. File contents are forced before publication; destination directories are also forced after renames on a best-effort basis where the filesystem permits directory fsync. Canonical source reads and ZIP entries use the committed snapshot. Startup and preview preparation remove uncommitted/staged snapshots and restore `output/main.tex` from the committed source. An interruption before metadata commit keeps the old revision; one after commit keeps the new one. History never comes from preview folders. Tests inject process/write interruptions; abrupt power-loss behavior has not been tested.

A history recovery failure keeps the job listed and retrievable, with original DOCX download and deletion available. Source/history/save/build/ZIP operations return an actionable conflict instead of exposing or overwriting an inconsistent revision. The source-error panel provides **Download original Word document**, and any browser-local draft remains downloadable while loading fails. Original-download errors stay inline and can be retried without navigating away. Back up the job directory, inspect the server log, repair history, then restart to revalidate it. Deletion markers take precedence so recovery never recreates partially removed jobs.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8080` | HTTP port (update the Vite proxy if changed) |
| `DOC2TEX_DATA_DIR` | `.data/jobs` | Persistent local job storage |
| `PANDOC_PATH` | `.tools/pandoc/pandoc[.exe]`, then PATH | Pandoc executable |
| `TECTONIC_PATH` | `.tools/tectonic/tectonic[.exe]`, then PATH | Tectonic executable |
| `DOC2TEX_FONT_DIR` | `.tools/fonts` | Optional multilingual font files and OFL licenses |

Worker counts, queue capacity, and timeouts are configured in `src/main/resources/application.properties`. Pending jobs are marked failed after an app restart; upload again or rebuild the preview. Completed results remain available. Job storage has no automatic expiry; use Delete in All documents to remove unwanted originals and results safely.

## Current scope

This is a **single-user local MVP**. It binds to `127.0.0.1` and rejects non-local origins/hosts. It has no accounts or durable queue broker. The bounded executor prevents unlimited concurrent converter processes, but it is not an operating-system sandbox. Pandoc runs with `--sandbox`; Tectonic runs with `--untrusted` and process timeouts. Before offering public uploads, put workers behind OS/container isolation with filesystem, network, CPU, memory, and disk limits, and add authentication and per-user storage.

DOCX is the supported input. Legacy `.doc`, PDFs, scans, Google Docs links, bibliography reconstruction, and journal-specific templates are future work. Pandoc preserves document structure rather than exact Word page layout; review complex tables, references, floating objects, legacy embedded equations, and font/language support. A successful PDF build checks compilability, not content fidelity. The editor accepts source up to 2 MB; DOCX archives are limited to 2,000 entries and 100 MB expanded size.

The Review tab reports common risks; it does not certify a lossless conversion. Source counts describe the original DOCX even after source edits. Tracked changes are explicitly accepted. Headers/footers, editable charts, comments, and embedded objects can require manual reconstruction. Pre-numbered captions can acquire duplicate labels. Missing glyphs and overfull layout warnings are surfaced even if the compiler exits successfully. Old conversions keep their existing source: convert them again to apply new font handling.

## Upstream tools

- [Spring Boot](https://docs.spring.io/spring-boot/)
- [Pandoc manual and conversion limitations](https://pandoc.org/MANUAL.html)
- [Tectonic](https://tectonic-typesetting.github.io/)
- [Vite](https://vite.dev/)

The Windows setup script pins Pandoc 3.12.1 and Tectonic 0.17.0 and checks their official release SHA-256 digests. These tools retain their upstream licenses.
