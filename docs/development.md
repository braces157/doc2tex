# Development and API reference

Return to the [project overview](../README.md).

A local DOCX → LaTeX app built with **Spring Boot 4.1.1, Java 21+, React, TypeScript, shadcn/ui, Vite, Pandoc, and Tectonic**.

## What works

- DOCX upload and drag and drop, up to 20 MB.
- Article and report templates.
- Background conversion with two workers and a bounded queue.
- Editable LaTeX source, save, copy, and `.tex` download.
- ZIP export with `main.tex`, extracted images, and conversion messages.
- PDF compilation and a PDF.js canvas viewer with page navigation and PDF download, requested separately from conversion.
- Local conversion history that survives application restarts.
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
| POST | `/api/jobs` | Multipart `file` and `template`; returns 202 and a job |
| GET | `/api/jobs` | Most recent 30 jobs |
| GET | `/api/jobs/{id}` | Conversion and preview status |
| GET | `/api/jobs/{id}/review` | Detected source features, review flags, and PDF compiler warnings |
| GET | `/api/jobs/{id}/source` | UTF-8 LaTeX source |
| PUT | `/api/jobs/{id}/source` | JSON `{ "source": "...", "revision": 0 }`; saves and invalidates the PDF |
| GET | `/api/jobs/{id}/download` | ZIP of saved LaTeX, images, and README |
| POST | `/api/jobs/{id}/preview` | Queue PDF compilation |
| GET | `/api/jobs/{id}/preview` | Current compiled PDF |
| GET | `/api/jobs/{id}/preview/log` | Compiler output, capped at 64 KB |

Conversions: `QUEUED → CONVERTING → COMPLETED / FAILED`.
Previews: `NOT_REQUESTED → QUEUED → COMPILING → READY / FAILED`.

Saving uses a revision check to prevent overwriting edits from another window. Saving is blocked while that job's preview is queued/compiling. Downloads include the latest saved source; the interface saves pending edits before exporting a ZIP or building a PDF.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8080` | HTTP port (update the Vite proxy if changed) |
| `DOC2TEX_DATA_DIR` | `.data/jobs` | Persistent local job storage |
| `PANDOC_PATH` | `.tools/pandoc/pandoc[.exe]`, then PATH | Pandoc executable |
| `TECTONIC_PATH` | `.tools/tectonic/tectonic[.exe]`, then PATH | Tectonic executable |
| `DOC2TEX_FONT_DIR` | `.tools/fonts` | Optional multilingual font files and OFL licenses |

Worker counts, queue capacity, and timeouts are configured in `src/main/resources/application.properties`. Pending jobs are marked failed after an app restart; upload again or rebuild the preview. Completed results remain available. Job storage currently has no automatic expiry: stop the app and remove individual job directories from `.data/jobs` to discard old originals and results.

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
