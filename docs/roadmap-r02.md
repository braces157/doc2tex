# R02 implementation evidence

Implemented 16 October 2026, Asia/Bangkok (UTC+7). R01 was reviewed and merged in [PR #7](https://github.com/braces157/doc2tex/pull/7) before this ticket began. [Issue #8](https://github.com/braces157/doc2tex/issues/8) records the R02 scope; review and merge state is in the [roadmap ledger](roadmap.md#progress-ledger).

Display names are separate, optional metadata with an empty default for old jobs. The library searches both name and original filename, shows both, and provides rename and explicit delete dialogs. The original filename, source, revision, template, and preview remain unchanged on rename. Clearing a display name restores filename-based titles.

Per-job read/write leases coordinate saves, metadata edits, workers, queued preview transitions, source/review/log reads, and the lifetime of PDF/ZIP writes. Deletion uses a nonblocking exclusive lease and rejects queued/running conversion/compilation. Lock identities remain stable for the process lifetime so delayed callers cannot acquire a new lock for a deleted job and recreate it.

Deletion checks the canonical UUID path, every ancestor, and all descendants without following links. Symlinks and Windows junction/reparse points are rejected before destructive work. A durable metadata tombstone precedes cleanup; failure leaves a visible retry entry (`deletionPending`) that returns 410 for project operations. Restart restores that state even after the original UUID directory is gone. Retry finishes cleanup; only then are the tombstone and listing removed. A corrupt tombstone fails startup rather than exposing a partially deleted project. See [API and recovery details](development.md#api).

Only successful deletion clears matching browser draft keys and active selection. Another tab confirms browser deletion notices with the server and checks on window focus. It retains its in-memory text and can download it after 404/410 responses; server save/build/export are blocked. A failed partial cleanup preserves the active key for retry. Local recovery drafts from stale library entries remain downloadable. There is no automatic expiration or bulk deletion.

## Verification

- Frontend lint and production build passed without lint warnings.
- Full backend verification with `./mvnw.cmd verify '-Ddoc2tex.test-pdf=true' '-Ddoc2tex.build-directory=target/roadmap-verification'`: 45 passed, zero skipped. Real Pandoc/Tectonic Article/Report corpus checks remain green.
- Nine lifecycle tests cover Unicode rename/restart and legacy metadata, full removal, interrupted cleanup and marker-removal failure/restart/retry, queued/busy leases, malformed markers, UUID traversal, Windows junction child/root protection, and preservation of test-owned files outside the job. Lifecycle tests were rerun after clarifying corrupt-marker errors.
- HTTP checks cover metadata validation, busy deletion, 204 success, and refusal to recreate source after deletion. Archive and PDF stream tests hold output behind a deterministic gate and prove deletion is rejected until the file operation finishes.
- `npm --prefix frontend run test:e2e`: 48 passed across desktop/mobile Chromium. Workflows cover rename/reload with a dirty draft, cancel/confirm, matching-key cleanup, busy and cleanup-failure retries, another tab's downloadable in-memory text, missing/pending-save responses, stale library recovery, and prior library/recovery protections.
- Desktop and mobile library screenshots inspected; controls and original/display names fit both viewports. All filesystem tests use isolated synthetic temporary storage, including external-path sentinels; no real `.data` jobs are cleaned up.

Windows local checks use Java 25.0.1 and Node 25.5.0; CI covers Java 21 / Node 24 on Windows and Linux. Screenshots and PDF evidence remain in ignored test-output directories. No live screen-reader session or customer study was performed. Filesystem leases coordinate this application process; they are not an operating-system sandbox against another process modifying storage concurrently.
