# Conversion verification

Tested locally on 15 October 2026 with Pandoc 3.12.1, Tectonic 0.17.0, Java 25, and the project-local Noto fonts. These are deterministic synthetic DOCX fixtures, not evidence that every possible Word document converts correctly.

## Results

| Fixture | Difficult features | Source checks | PDF checks | Review needed |
|---|---|---|---|---|
| Research methods | Fraction, radical, matrix, nested lists, horizontal/vertical table merges, PNG chart image, footnote, hyperlink, special characters | Required text and native LaTeX math retained; Article and Report tested | Two pages; checkpoint text found; no missing-glyph warnings | Merged-cell alignment and duplicate pre-numbered figure caption |
| Multilingual notes | French, Vietnamese, Greek, Russian, Thai, Chinese, Japanese, Arabic | All eight phrases retained in Unicode source; Article and Report tested | One page; no missing-glyph warnings after Noto support | Font appearance, script shaping, language-specific line breaks, and mixed-direction text |
| Edited operations | Tracked insertion/deletion, text box, header/footer text, chart part, review comments | Accepted text retained; deleted text excluded; Article and Report tested | One page; accepted body checkpoints found | Headers/footers excluded from body; charts/comments flagged; text box reading order |
| Observation register | Forty sections, ninety-row table, repeated table headers, final-page checkpoint | First/middle/last section checkpoints and last table row retained; Article and Report tested | Ten pages; final checkpoint found; no missing-glyph warnings | Normal proofreading and pagination review |

In the warm HTTP run with two concurrent requests, conversion completed in **0.281–0.296 seconds** and PDF compilation in **1.563–2.219 seconds**. These measurements include polling resolution and apply to these small fixtures on this machine. They are not general performance guarantees. First-use TeX downloads take longer.

The multilingual baseline produced **208 missing-character warnings while still reporting a successful PDF build**. Conditional Noto font bundling and script handling reduced that count to zero for this fixture. Font files and their licenses are included in multilingual ZIP exports; these exports require XeLaTeX.

## Verification performed

- **23 backend tests passed**, with PDF tests enabled and no skipped tests.
- Four difficult source fixtures were converted using both Article and Report templates.
- Four real PDFs were compiled, checked for missing-glyph warnings, and inspected as rendered page images. The long document's contact sheet covers all ten pages.
- Required source checkpoints, accepted/deleted revision behavior, native math commands, footnote commands, and image inclusion were asserted.
- HTTP ZIP exports were checked for source, conversion review JSON, and applicable font/license assets.
- Input checks cover extension/content mismatch, malformed archives, traversal, macros, XML external entities, and compressed expansion limits.
- Browser checks cover desktop/mobile layout, keyboard tab navigation, PDF page navigation, edit protection, download controls, review details, and mobile history.

## Limits exposed by testing

- **Compilation is not a content-fidelity test.** A PDF can compile while omitting text or lacking glyphs. The review panel now exposes common source risks and compiler warnings.
- Word headers and footers do not become body text automatically. Essential content must be recreated in LaTeX.
- Pre-numbered figure captions can be numbered a second time by LaTeX; edit the caption to remove the duplicate.
- Merged table content survives the fixture, but cell geometry and page layout can change.
- Editable chart and comment parts are detected and flagged; this test does not claim complete chart rendering or comment preservation.
- The multilingual fixture covers specific phrases. It does not prove support for every Unicode script, font, punctuation convention, or bidirectional layout.
- No LibreOffice installation is available on this Windows host, so the original DOCX fixtures could not be rendered with the bundled `render_docx.py`. Verification combines structured source checks, compiler checks, PDF extraction, and visual inspection of the converted PDFs; an exact Word-to-PDF visual comparison remains unverified.
- PDF, scanned images, and legacy `.doc` files remain unsupported inputs. No claim of universal document conversion is made.

## Repeat the checks

```powershell
# From the project root, after tool/font setup.
./mvnw.cmd test '-Ddoc2tex.test-pdf=true'

# Against the running app; Python needs pypdf and Pillow, and Poppler must be on PATH.
python scripts/check-corpus.py
```

Fixtures: `src/test/resources/corpus/`. Machine-readable expectations: `manifest.json` in that directory. HTTP evidence and rendered contact sheets: `.data/corpus-results/`. The evidence directory is ignored by Git because it contains generated jobs and PDFs.
