# doc2TeX Design System

## Palette & Surfaces
- **Canvas (`--surface`):** `#f5f3ee` (sidebar `#f0ede6`, sunken `--surface-sunken`: `#ece9e1`)
- **Card / Background (`--background`, `--card`, `--popover`):** `#ffffff`
- **Ink:** `--foreground` `#181715`, `--subtle` `#4a4742`, `--muted-foreground` `#6e6a63`, `--faint` `#9b958b`
- **Primary Accent:** `--primary` `#1f4d3a`, `--primary-hover` `#173b2c`, `--primary-soft` `#eaf2ee`, `--primary-ink` `#163a2b`
- **Semantic Status:**
  - Success: `#15803d` on `#ecfdf3` (`--success-border`: `#bbf7d0`)
  - Warning: `#b45309` on `#fffbeb` (`--warning-border`: `#fde68a`)
  - Danger: `#b91c1c` on `#fef2f2` (`--danger-border`: `#fecaca`)
- **Borders:** `--border` `#e2ddd3`, `--border-strong` `#cfc9bc`

## Typography
- **UI Sans (`--font-sans`):** `"Geist Variable", -apple-system, "Segoe UI", sans-serif` (14px base)
- **Code / Mono (`--font-mono`):** `"Geist Mono Variable", "Cascadia Code", "Consolas", monospace` (13px editor, `tabular-nums` for line/count metrics)

## Layout, Elevation & Motion
- **Shell:** 248px sticky sidebar (with `DOCX → LaTeX` / `PDF Studio` / `Format Hub` / `Compare & Clean` 2x2 mode switcher and mobile switcher strip) + fluid main workspace (`max-width: 1560px`). Once a document is loaded, `1 Source document` collapses to a compact header strip so `2 LaTeX output` uses the full width.
- **Overlays:** Modals and dropdowns use opaque `--background` / `--popover` surfaces (`z-index: 50`) over a `z-index: 40` backdrop.
- **Motion:** 150ms color/border transitions; all animations disabled under `prefers-reduced-motion: reduce`.

## Six Edge States
1. **Empty:** Clear next action (`Choose a document` / `Try it with a sample document`, `No conversions yet`, `No review yet`).
2. **Loading:** Geometry-matched editor skeleton rows and progress bars with `aria-busy`.
3. **Overflow & Density:** Single-line ellipsis truncation on filenames/outline headings, scrollable dialog bodies with pinned footers, responsive 3-col -> 2-col metric grids.
4. **Error & Offline:** Inline `role="alert"` banners with `Retry` and `Download unsaved text` / `Download original Word document` fallbacks.
5. **Keyboard & Focus:** `skip-link`, `:focus-visible` ring (`--ring`), `aria-label` on icon-only buttons, keyboard shortcuts (`Ctrl/Cmd+S`, `Ctrl/Cmd+Enter`).
6. **Contrast:** `>= 4.5:1` for body copy and `>= 3:1` for interactive controls.
