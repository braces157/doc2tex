import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import type { ConversionReview, Job, SavedHistory } from '../src/api'
import type { PdfInspection } from '../src/pdfApi'

const JOB_ID = '9d3e6120-7422-4f9b-83b0-1c82b6f7a111'
const ZIP_BYTES = Buffer.from('PK\x05\x06' + '\x00'.repeat(18), 'binary')
const TINY_PNG_BASE64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII='

function minimalPdfBytes(): Buffer {
  const parts = [
    '%PDF-1.4\n',
    '1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n',
    '2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n',
    '3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >>\nendobj\n',
  ]
  let offset = parts[0].length
  const offsets: number[] = []
  for (let i = 1; i < parts.length; i++) {
    offsets.push(offset)
    offset += parts[i].length
  }
  const xref = `xref\n0 4\n0000000000 65535 f \n${offsets.map(n => `${String(n).padStart(10, '0')} 00000 n `).join('\n')}\n`
  const trailer = `trailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n${offset}\n%%EOF\n`
  return Buffer.from(parts.join('') + xref + trailer, 'ascii')
}

const PDF_BYTES = minimalPdfBytes()

function inspectionFor(filename: string): PdfInspection {
  return {
    filename,
    bytes: PDF_BYTES.length,
    pdfVersion: '1.4',
    pageCount: 1,
    encrypted: false,
    hasMetadata: true,
    hasXmpMetadata: false,
    hasOutline: true,
    embeddedImageCount: 1,
    annotationCount: 0,
    fontCount: 2,
    metadata: {
      title: 'Sample Study',
      author: 'Researcher',
      subject: '',
      keywords: '',
      creator: 'doc2TeX',
      producer: 'PDFBox',
      creationDate: '2026-11-21T00:00:00Z',
      modificationDate: '',
    },
    pages: [{ number: 1, widthMm: 215.9, heightMm: 279.4, rotation: 0, charCount: 420, imageCount: 1, annotationCount: 0 }],
    outline: [{ title: '1 Introduction', level: 0, pageNumber: 1 }],
    warnings: [],
  }
}

async function switchMode(page: Page, mode: 'DOCX → LaTeX' | 'PDF Studio' | 'Format Hub' | 'Compare & Clean') {
  const isMobile = (page.viewportSize()?.width ?? 1280) <= 640
  const nav = page.getByRole('navigation', { name: isMobile ? 'Studio tools mobile' : 'Studio tools', exact: true })
  await nav.getByRole('button', { name: mode, exact: true }).click()
}

async function setupEcosystemMocks(page: Page) {
  const job: Job = {
    id: JOB_ID,
    filename: 'manuscript.docx',
    template: 'article',
    status: 'COMPLETED',
    previewStatus: 'READY',
    message: 'Conversion complete.',
    previewMessage: 'PDF ready.',
    createdAt: '2026-11-21T09:00:00Z',
    updatedAt: '2026-11-21T10:00:00Z',
    size: 4096,
    revision: 2,
  }
  const review: ConversionReview = {
    elements: { paragraphs: 12 },
    findings: [],
    compilerWarnings: [],
    scope: 'Test scope',
    revision: 2,
  }
  const savedHistory: SavedHistory = {
    items: [
      { revision: 2, createdAt: '2026-11-21T10:00:00Z', bytes: 82, origin: 'saved', restoredFrom: 0 },
      { revision: 1, createdAt: '2026-11-21T09:00:00Z', bytes: 78, origin: 'converted', restoredFrom: 0 },
    ],
    currentRevision: 2,
    firstRevision: 1,
    legacyHistoryUnavailable: false,
    maxVersions: 25,
    maxBytes: 10485760,
    bytesUsed: 160,
  }

  const downloadCalls: string[] = []

  await page.context().route('**/api/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const json = (body: unknown, status = 200) => route.fulfill({ status, json: body })

    if (path === '/api/system') {
      return json({ pandoc: true, tectonic: true, templates: ['article', 'report'], maxUploadBytes: 20971520 })
    }
    if (path === '/api/jobs') return json([job])
    if (path === '/api/library') return json({ items: [job], page: 0, size: 20, total: 1 })
    if (path === `/api/jobs/${JOB_ID}`) return json(job)
    if (path === `/api/jobs/${JOB_ID}/source` || path === `/api/jobs/${JOB_ID}/versions/2/source`) {
      return route.fulfill({
        status: 200,
        contentType: 'text/plain;charset=utf-8',
        body: '\\documentclass{article}\n\\begin{document}\nUpdated line.\n\\end{document}',
      })
    }
    if (path === `/api/jobs/${JOB_ID}/versions/1/source`) {
      return route.fulfill({
        status: 200,
        contentType: 'text/plain;charset=utf-8',
        body: '\\documentclass{article}\n\\begin{document}\nOld line.\n\\end{document}',
      })
    }
    if (path === `/api/jobs/${JOB_ID}/versions`) return json(savedHistory)
    if (path === `/api/jobs/${JOB_ID}/review`) return json(review)
    if (path === `/api/jobs/${JOB_ID}/diagnostics`) {
      return json({ revision: 2, status: 'ready', logTruncated: false, diagnosticsTruncated: false, items: [] })
    }
    if (path === `/api/jobs/${JOB_ID}/preview`) {
      return route.fulfill({ status: 200, contentType: 'application/pdf', body: PDF_BYTES })
    }
    if (path.startsWith(`/api/jobs/${JOB_ID}/export/`)) {
      downloadCalls.push(path)
      return route.fulfill({ status: 200, contentType: 'application/octet-stream', body: ZIP_BYTES })
    }

    if (path === '/api/pdf/inspect') return json(inspectionFor('sample.pdf'))
    if (path === '/api/pdf/to-source') {
      return json({
        filename: 'sample.pdf',
        format: 'markdown',
        pageCount: 1,
        headingsFound: 2,
        imagesExtracted: 1,
        twoColumnDetected: true,
        aiLayoutUsed: true,
        warnings: ['Converted two-column layout in left-to-right column order.'],
        content: '# Reconstructed Paper\n\nTwo-column text cleanly ordered.\n',
      })
    }
    if (path === '/api/pdf/to-source/download') {
      downloadCalls.push(path)
      return route.fulfill({
        status: 200,
        contentType: 'application/zip',
        headers: { 'Content-Disposition': 'attachment; filename="sample-latex.zip"' },
        body: ZIP_BYTES,
      })
    }

    if (path === '/api/convert/formats') {
      return json({
        pandocReady: true,
        legacyDocReady: false,
        inputs: [
          { id: 'markdown', label: 'Markdown (.md)', extension: '.md' },
          { id: 'docx', label: 'Word (.docx)', extension: '.docx' },
        ],
        outputs: [
          { id: 'docx', label: 'Word Document (.docx)', extension: '.docx' },
          { id: 'markdown', label: 'Markdown (.md)', extension: '.md' },
          { id: 'html', label: 'Self-contained HTML (.html)', extension: '.html' },
          { id: 'typst', label: 'Typst (.typ)', extension: '.typ' },
        ],
      })
    }
    if (path === '/api/convert') {
      downloadCalls.push(path)
      return route.fulfill({
        status: 200,
        contentType: 'text/html;charset=utf-8',
        headers: {
          'Content-Disposition': 'attachment; filename="notes.html"',
          'X-Doc2tex-Warnings': '',
        },
        body: '<!doctype html><html><body><h1>Converted HTML</h1></body></html>',
      })
    }
    if (path === '/api/batch/convert') {
      downloadCalls.push(path)
      return route.fulfill({
        status: 200,
        contentType: 'application/zip',
        headers: {
          'Content-Disposition': 'attachment; filename="batch-markdown.zip"',
          'X-Doc2tex-Batch-Summary': JSON.stringify({ total: 2, succeeded: 2, failed: 0 }),
        },
        body: ZIP_BYTES,
      })
    }

    if (path === '/api/templates') {
      return json([
        { id: 'ieee-twocol', name: 'IEEE-Style Conference (2-col)', category: 'Conference', description: 'Two-column IEEE template', defaultPaper: 'letter', defaultFontSize: 10, twoColumn: true },
        { id: 'acm-manuscript', name: 'ACM-Style Manuscript', category: 'Journal', description: 'Single-column ACM template', defaultPaper: 'letter', defaultFontSize: 11, twoColumn: false },
      ])
    }
    if (path === '/api/templates/generate') {
      return json({
        templateId: 'acm-manuscript',
        filename: 'acm-manuscript.tex',
        source: '\\documentclass[11pt,letterpaper]{article}\n\\title{Reproducible Local Publishing}\n\\begin{document}\n\\maketitle\n\\end{document}',
      })
    }
    if (path === '/api/templates/compile') {
      downloadCalls.push(path)
      return route.fulfill({
        status: 200,
        contentType: 'application/pdf',
        headers: { 'Content-Disposition': 'attachment; filename="acm-manuscript.pdf"' },
        body: PDF_BYTES,
      })
    }
    if (path === '/api/references/extract') {
      return json({
        sourceName: 'pasted-references.txt',
        totalFound: 1,
        entries: [{ key: 'ref_10_1145', type: 'article', title: 'Reference', author: 'Unknown', year: '2026', doi: '10.1145/123456.7890', arxiv: '', raw: '10.1145/123456.7890' }],
        bibtex: '@article{ref_10_1145,\n  doi = {10.1145/123456.7890},\n  year = {2026},\n  note = {Extracted offline from pasted-references.txt}\n}\n',
        warnings: [],
      })
    }

    if (path === '/api/diff/pdf') {
      return json({
        leftName: 'v1.pdf',
        rightName: 'v2.pdf',
        leftPages: 1,
        rightPages: 1,
        comparedPages: 1,
        matchingPages: 0,
        changedPages: 1,
        addedLines: 2,
        removedLines: 1,
        truncated: false,
        pages: [{
          page: 1,
          identical: false,
          added: 2,
          removed: 1,
          lines: [
            { type: 'same', text: '1 Introduction' },
            { type: 'del', text: 'Previous hypothesis wording.' },
            { type: 'add', text: 'Updated empirical claim.' },
            { type: 'add', text: 'Added verification table.' },
          ],
        }],
      })
    }
    if (path === '/api/diff/pdf/page') {
      return json({
        page: 1,
        pixelDiffRatio: 0.0425,
        width: 1,
        height: 1,
        leftPngBase64: TINY_PNG_BASE64,
        rightPngBase64: TINY_PNG_BASE64,
        diffPngBase64: TINY_PNG_BASE64,
      })
    }
    if (path === '/api/docx/inspect') {
      return json({
        filename: 'report.docx',
        totalBytes: 512000,
        mediaBytes: 420000,
        mediaCount: 2,
        commentCount: 3,
        trackedChangeCount: 2,
        externalLinkCount: 1,
        metadata: { creator: 'Jane Reviewer', lastModifiedBy: 'Coauthor', company: 'Lab' },
        largestMedia: [{ name: 'word/media/image1.png', bytes: 320000 }],
        recommendations: ['Remove 3 review comments before double-blind submission.', 'Downscale large embedded PNG images to shrink file size.'],
      })
    }
    if (path === '/api/docx/clean') {
      downloadCalls.push(path)
      return route.fulfill({
        status: 200,
        contentType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        headers: {
          'Content-Disposition': 'attachment; filename="report-cleaned.docx"',
          'X-Doc2tex-Original-Bytes': '512000',
          'X-Doc2tex-Cleaned-Bytes': '184000',
          'X-Doc2tex-Images-Optimized': '2',
        },
        body: ZIP_BYTES,
      })
    }

    return json({ message: `Unexpected mock route ${path}` }, 404)
  })

  return { downloadCalls }
}

test('PDF Studio Convert to Editable converts a loaded PDF and prepares source downloads', async ({ page }) => {
  const { downloadCalls } = await setupEcosystemMocks(page)
  await page.goto('/')
  await switchMode(page, 'PDF Studio')

  await page.locator('input[aria-label="Upload PDF files"]').setInputFiles([
    { name: 'sample.pdf', mimeType: 'application/pdf', buffer: PDF_BYTES },
  ])
  await expect(page.getByRole('heading', { name: 'Loaded PDFs (1)', exact: true })).toBeVisible()

  await page.getByRole('tab', { name: 'Convert to Editable' }).click()
  await page.getByRole('button', { name: 'Convert PDF' }).click()

  await expect(page.getByText('2-column')).toBeVisible()
  await expect(page.getByText('ONNX AI')).toBeVisible()
  await expect(page.getByLabel('Converted PDF source preview')).toHaveValue(/# Reconstructed Paper/)

  await page.getByRole('button', { name: 'LaTeX + media (.zip)' }).click()
  await expect.poll(() => downloadCalls.includes('/api/pdf/to-source/download')).toBe(true)
})

test('Format Hub converts formats, processes a batch ZIP, generates templates, and extracts BibTeX without overflow', async ({ page }) => {
  const { downloadCalls } = await setupEcosystemMocks(page)
  await page.goto('/')
  await switchMode(page, 'Format Hub')

  await expect(page.getByRole('heading', { name: 'Format Hub', exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)).toBe(false)

  await page.locator('input[aria-label="Upload document for format conversion"]').setInputFiles({
    name: 'notes.md',
    mimeType: 'text/markdown',
    buffer: Buffer.from('# Hello\n', 'utf8'),
  })
  await page.locator('#hub-target-format').selectOption('html')
  await page.getByRole('button', { name: 'Convert & download' }).click()
  await expect(page.getByLabel('Converted text preview')).toHaveValue(/Converted HTML/)

  await page.getByRole('tab', { name: 'Batch Queue' }).click()
  await page.locator('input[aria-label="Upload multiple files for batch conversion"]').setInputFiles([
    { name: 'one.md', mimeType: 'text/markdown', buffer: Buffer.from('# One\n', 'utf8') },
    { name: 'two.md', mimeType: 'text/markdown', buffer: Buffer.from('# Two\n', 'utf8') },
  ])
  await expect(page.getByText('2 queued files')).toBeVisible()
  await page.getByRole('button', { name: 'Run batch ZIP' }).click()
  await expect(page.getByText(/Last batch batch-markdown\.zip: 2 succeeded, 0 failed out of 2/)).toBeVisible()

  await page.getByRole('tab', { name: 'Templates & Citations' }).click()
  await page.locator('#tpl-select').selectOption('acm-manuscript')
  await page.getByRole('button', { name: 'Generate .tex' }).click()
  await expect(page.getByLabel('Generated template LaTeX')).toHaveValue(/\\documentclass/)

  await page.getByRole('button', { name: 'Compile PDF' }).click()
  await expect.poll(() => downloadCalls.includes('/api/templates/compile')).toBe(true)

  await page.locator('#ref-paste-input').fill('10.1145/123456.7890')
  await page.getByRole('button', { name: 'Extract .bib entries' }).click()
  await expect(page.getByText('1 BibTeX entry found')).toBeVisible()
  await expect(page.getByLabel('Extracted BibTeX output')).toHaveValue(/@article\{ref_10_1145/)
})

test('Compare & Clean diffs PDFs visually and scrubs DOCX bloat without overflow', async ({ page }) => {
  await setupEcosystemMocks(page)
  await page.goto('/')
  await switchMode(page, 'Compare & Clean')

  await expect(page.getByRole('heading', { name: 'Compare & Clean', exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)).toBe(false)

  await page.locator('input[aria-label="Upload baseline left PDF"]').setInputFiles({
    name: 'v1.pdf',
    mimeType: 'application/pdf',
    buffer: PDF_BYTES,
  })
  await page.locator('input[aria-label="Upload revised right PDF"]').setInputFiles({
    name: 'v2.pdf',
    mimeType: 'application/pdf',
    buffer: PDF_BYTES,
  })
  await page.getByRole('button', { name: 'Compare PDFs' }).click()

  await expect(page.getByText('+2', { exact: true })).toBeVisible()
  await expect(page.getByText('Updated empirical claim.')).toBeVisible()
  await page.getByRole('button', { name: 'Render visual pixel diff' }).click()
  await expect(page.getByRole('heading', { name: 'Visual pixel diff · Page 1' })).toBeVisible()
  await expect(page.getByAltText('Visual diff overlay for page 1')).toBeVisible()

  await page.getByRole('tab', { name: 'DOCX Pre-Flight Cleaner' }).click()
  await page.locator('input[aria-label="Upload DOCX for pre-flight cleaning"]').setInputFiles({
    name: 'report.docx',
    mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    buffer: ZIP_BYTES,
  })
  await expect(page.getByText('Jane Reviewer')).toBeVisible()
  await page.getByRole('button', { name: 'Clean & download .docx' }).click()
  await expect(page.getByText(/Cleaned copy: 500\.0 KB → 179\.7 KB \(2 image\(s\) optimized\)/)).toBeVisible()
})

test('Saved versions supports Diff vs current and download options include Pandoc round-trip formats', async ({ page }) => {
  await setupEcosystemMocks(page)
  await page.goto('/')
  await page.getByRole('button', { name: 'All documents', exact: true }).click()
  await page.getByRole('dialog', { name: 'All documents' }).getByRole('button', { name: 'Open manuscript.docx', exact: true }).click()
  await expect(page.getByRole('button', { name: 'More download options' })).toBeEnabled()

  await page.getByRole('button', { name: 'More download options' }).click()
  await expect(page.getByRole('menuitem', { name: 'Word (.docx)' })).toBeVisible()
  await expect(page.getByRole('menuitem', { name: 'Markdown (.md)' })).toBeVisible()
  await expect(page.getByRole('menuitem', { name: 'Typst (.typ)' })).toBeVisible()
  await page.keyboard.press('Escape')

  await page.getByRole('button', { name: 'Saved versions' }).click()
  await page.getByRole('button', { name: /Version 1/ }).click()
  await page.getByRole('button', { name: 'Diff vs current' }).click()
  await expect(page.getByLabel('Diff between version 1 and version 2')).toContainText('Updated line.')
  await expect(page.getByLabel('Diff between version 1 and version 2')).toContainText('Old line.')
})
