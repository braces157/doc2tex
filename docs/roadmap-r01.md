# R01 implementation evidence

Implemented 16 October 2026. Review and merge status is tracked in the pull request and [roadmap ledger](roadmap.md#progress-ledger).

The All documents dialog searches the complete persisted collection on the server before pagination. It includes filename, creation time, template and conversion status; retains recent history; provides status filtering, newest/oldest sorting, explicit search, and previous/next controls. Page size defaults to 20 and the API accepts 1–100. Creation-date ties always use ascending job ID. Search uses locale-independent case folding with trimmed surrounding whitespace. The API is documented in [development](development.md#api).

Requests are cancelled when filters change or the dialog closes. A request identity also hides older results during loading, including reopening the dialog. Selection uses the existing source-switch/discard confirmation. Active document restoration fetches its ID directly and ignores a delayed response after user navigation. No converter behavior, storage format, or source revision ownership changes were needed.

## Verification

- `npm --prefix frontend run lint`: passed without warnings.
- `npm --prefix frontend run build`: passed.
- `npm --prefix frontend run test:e2e`: 34 passed, covering desktop and mobile Chromium. Includes pagination across 65 documents, older-than-30 search/reopen/reload, combined filters and sorting, empty/retry/loading states, stale request handling, keyboard document opening, dirty-source cancel/confirm, recovery, and existing source conflict/storage-failure tests.
- `./mvnw.cmd verify '-Ddoc2tex.test-pdf=true' '-Ddoc2tex.build-directory=target/roadmap-verification'`: 34 passed, zero skipped. Includes real Pandoc/Tectonic PDF fixtures for both templates, 65-job restart/pagination/filter/tie tests, direct older-ID lookup, bounds/overflow checks, and HTTP validation.
- Desktop and mobile library screenshots inspected. The dialog fits both viewports, text and controls remain readable, and filenames can wrap. Browser checks use synthetic API mocks; durable storage and real conversions are covered separately by backend tests in isolated temporary directories.

Local environment: Windows, Java 25.0.1, Node 25.5.0. CI verifies Java 21 / Node 24 on Windows and Linux. Generated screenshots and PDF evidence are under ignored `frontend/test-results/` and `target/roadmap-verification/`; no personal documents or real job storage were used for tests.

Screen-reader labeling is checked through accessible-name browser locators. No live assistive-technology session or customer study was performed. Search currently scans and sorts in-memory job metadata; it introduces no database index.
