# Contributing to doc2TeX

Small, focused improvements are welcome. For a substantial feature or a new input format, open an issue first so the approach and scope can be discussed.

## Set up

1. Install Java 21+ and Node.js 24 LTS.
2. Clone your fork and install the project tools using the [README](README.md#quick-start).
3. Run `npm ci` in `frontend`.
4. Start the backend and frontend as described in [development](docs/development.md).

The Maven Wrapper is included; a system Maven installation is optional.

## Before opening a pull request

```powershell
npm --prefix frontend ci
npm --prefix frontend run lint
npm --prefix frontend run build
./mvnw.cmd verify '-Ddoc2tex.test-pdf=true'
```

Use `./mvnw` on Linux/macOS. The full PDF suite needs Pandoc, Tectonic, and the Noto font assets. First-use compilation downloads TeX packages.

- Explain the problem, the resulting behavior, and how you verified it.
- Add a regression check when changing conversion behavior or fixing a parsing bug. Preserve the existing corpus expectations unless the behavior is intentionally changing.
- Check the interface at desktop and mobile widths when editing UI code. Keep keyboard navigation and reduced-motion support working.
- Update relevant documentation when changing setup, APIs, limits, or supported features.
- Keep generated jobs, PDFs, credentials, tool binaries, and personal documents out of commits.

## Reproducing a conversion issue

Use a small synthetic DOCX whenever possible. Include the failing feature, expected output, actual output, tool versions, operating system, and relevant sanitized compiler messages. Successful compilation alone does not prove content preservation.

Do not upload confidential documents to public issues. Remove personal information, comments, revision authors, headers/footers, and document metadata from any shared fixture. A rewritten synthetic example is safer than editing a private original.

## Project conventions

- Java backend: small services in `src/main/java/dev/doc2tex`; meaningful tests in `src/test/java`.
- Frontend: TypeScript and React; customized shadcn/Radix components and Phosphor icons.
- Run converters with argument arrays, process deadlines, and explicit working directories. Never concatenate a shell command from document text or filenames.
- Keep the app local by default. Changes that accept remote uploads need an explicit security design.
- Include required third-party notices when adding or redistributing dependencies. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Contributions are licensed under the project's [MIT license](LICENSE). Be respectful and follow the [community guidelines](CODE_OF_CONDUCT.md).
