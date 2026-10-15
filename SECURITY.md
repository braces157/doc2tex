# Security policy

## Supported scope

Security fixes target the latest code on `main`. doc2TeX currently serves a single-user **local workspace**. It is not a hardened public document-processing service.

## Report a vulnerability privately

Use [GitHub private vulnerability reporting](https://github.com/braces157/doc2tex/security/advisories/new). Describe the affected version, operating system, reproduction steps, impact, and a minimal synthetic document if relevant.

Do not open a public issue containing an exploit, private document, credential, or sensitive filesystem content. If private reporting is unavailable, open a neutral issue asking the maintainer to enable a private reporting channel; omit the vulnerability details.

Reports are reviewed on a best-effort basis. Avoid testing against other people's documents, machines, or hosted instances.

## Trust boundaries

- The default listener is `127.0.0.1`; non-local hosts and cross-site browser requests are rejected.
- Uploads are restricted to DOCX, 20 MB compressed, 100 MB expanded, and 2,000 ZIP entries.
- Archive traversal, macro payloads, and XML external entities are rejected or disabled.
- Pandoc runs with `--sandbox`; Tectonic runs with `--untrusted`. Process counts, queue length, and operation duration are bounded.
- These settings **do not provide an operating-system sandbox**. TeX engines, native image readers, and document parsers still process complex untrusted data.
- Original uploads, source, previews, and metadata are retained locally until removed. There is no encryption-at-rest or automatic retention policy.
- TeX package downloads and tool/font setup need network access. Documents are not submitted to an external conversion API by the app.

Before supporting public uploads, add authentication, isolated workers, per-user storage, network/filesystem boundaries, CPU/memory/disk limits, retention controls, rate limits, and a deployment-specific security review. Do not expose the current listener to the internet by forwarding its port.

Keep `.data/`, `.tools/`, credentials, and personal documents out of version control. Public screenshots should come from a synthetic demo workspace.
