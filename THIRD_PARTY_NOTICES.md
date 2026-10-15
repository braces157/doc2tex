# Third-party software

The [MIT license](LICENSE) covers doc2TeX's original code. Dependencies and downloaded tools keep their own licenses.

| Component | License | Distribution |
|---|---|---|
| Spring Boot and Spring Framework | Apache-2.0 | Maven dependencies in the executable JAR; upstream notices retained |
| React, Radix, Phosphor, Tailwind, and related frontend packages | Mostly MIT; see exact inventory | Compiled browser assets |
| PDF.js | Apache-2.0 | Browser PDF viewer and worker |
| Playwright | Apache-2.0 | Development-only browser test runner; not included in application assets |
| Apache PDFBox | Apache-2.0 | Test-only PDF text inspection; not included in the application JAR |
| Geist font | SIL OFL-1.1 | Bundled browser font |
| Customized shadcn/ui components | MIT | Source in `frontend/src/components/ui`; [license](LICENSES/shadcn-ui.txt) |
| Apache Maven Wrapper | Apache-2.0 | Wrapper scripts retain their upstream headers |
| Pandoc | GPL-2.0-or-later | Separately downloaded executable, invoked as a process |
| Tectonic | MIT | Separately downloaded executable, invoked as a process |
| Noto fonts | SIL OFL-1.1 | Separately downloaded; applicable fonts and licenses included in document ZIP exports |

[Frontend dependency notices](LICENSES/frontend-dependencies.txt) contain the installed production packages' license and notice files. Refresh them after dependency updates:

```sh
npm --prefix frontend ci
node scripts/collect-licenses.mjs
```

The notices are packaged under `META-INF/licenses/` in the application JAR. Maven dependency JARs retain their own license and notice files. Tool binaries and document fonts are excluded from Git; setup scripts download pinned upstream artifacts with SHA-256 checks. TeX packages downloaded by Tectonic have their own upstream licenses. Redistributors should review the licenses for every tool, font, and TeX package they include.

Upstream projects: [Pandoc](https://github.com/jgm/pandoc), [Tectonic](https://github.com/tectonic-typesetting/tectonic), [Noto](https://github.com/notofonts), [PDF.js](https://github.com/mozilla/pdf.js), [Geist](https://github.com/vercel/geist-font), [shadcn/ui](https://github.com/shadcn-ui/ui).
