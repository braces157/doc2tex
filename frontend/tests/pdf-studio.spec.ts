import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import type { ConversionReview, Job } from '../src/api'
import type { PdfAssemblePlan, PdfInspection } from '../src/pdfApi'

const JOB_ID = '8e2c5256-a169-4ef0-9c71-2a52a8a5f6f0'

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

function inspectionFor(filename: string, pageCount: number): PdfInspection {
  return {
    filename,
    bytes: PDF_BYTES.length,
    pdfVersion: '1.4',
    pageCount,
    encrypted: false,
    hasMetadata: true,
    hasXmpMetadata: filename === 'alpha.pdf',
    hasOutline: filename === 'alpha.pdf',
    embeddedImageCount: filename === 'alpha.pdf' ? 2 : 0,
    annotationCount: filename === 'alpha.pdf' ? 1 : 0,
    fontCount: 2,
    metadata: {
      title: filename === 'alpha.pdf' ? 'Experimental Paper' : 'Appendix',
      author: 'Ada Lovelace',
      subject: 'Mathematics',
      keywords: 'engines',
      creator: 'doc2TeX',
      producer: 'PDFBox',
      creationDate: '2026-11-21T00:00:00Z',
      modificationDate: '',
    },
    pages: Array.from({ length: pageCount }, (_, i) => ({
      number: i + 1,
      widthMm: 215.9,
      heightMm: 279.4,
      rotation: 0,
      charCount: 120,
      imageCount: filename === 'alpha.pdf' && i === 0 ? 2 : 0,
      annotationCount: filename === 'alpha.pdf' && i === 0 ? 1 : 0,
    })),
    outline: filename === 'alpha.pdf' ? [{ title: '1 Introduction', level: 0, pageNumber: 1 }] : [],
    warnings: filename === 'alpha.pdf'
      ? [
          'Document contains metadata (such as Author, Creator, or XMP). Use Privacy Scrub before double-blind sharing.',
          'Document contains 1 annotations or comments.',
        ]
      : ['Document contains metadata (such as Author, Creator, or XMP). Use Privacy Scrub before double-blind sharing.'],
  }
}

async function switchMode(page: Page, mode: 'DOCX → LaTeX' | 'PDF Studio') {
  const isMobile = (page.viewportSize()?.width ?? 1280) <= 640
  const nav = page.getByRole('navigation', { name: isMobile ? 'Studio tools mobile' : 'Studio tools', exact: true })
  await nav.getByRole('button', { name: mode, exact: true }).click()
}

async function setupPdfMocks(page: Page, options?: { imageErrorFirst?: boolean }) {
  const job: Job = {
    id: JOB_ID,
    filename: 'draft.docx',
    template: 'article',
    status: 'COMPLETED',
    previewStatus: 'READY',
    message: 'Conversion complete.',
    previewMessage: 'PDF ready.',
    createdAt: '2026-11-21T10:00:00Z',
    updatedAt: '2026-11-21T10:00:00Z',
    size: 2048,
    revision: 0,
  }
  const review: ConversionReview = {
    elements: { paragraphs: 4 },
    findings: [],
    compilerWarnings: [],
    scope: 'Test scope',
    revision: 0,
  }
  let imageCalls = 0
  const assemblePlans: PdfAssemblePlan[] = []

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
    if (path === `/api/jobs/${JOB_ID}/source`) {
      return route.fulfill({ status: 200, contentType: 'text/plain;charset=utf-8', body: '\\documentclass{article}\n\\begin{document}\nHello world.\n\\end{document}' })
    }
    if (path === `/api/jobs/${JOB_ID}/review`) return json(review)
    if (path === `/api/jobs/${JOB_ID}/diagnostics`) return json({ revision: 0, status: 'ready', logTruncated: false, diagnosticsTruncated: false, items: [] })
    if (path === `/api/jobs/${JOB_ID}/preview`) return route.fulfill({ status: 200, contentType: 'application/pdf', body: PDF_BYTES })

    if (path === '/api/pdf/inspect') {
      const bodyText = request.postDataBuffer()?.toString('latin1') ?? ''
      const filename = bodyText.includes('filename="beta.pdf"') ? 'beta.pdf' : 'alpha.pdf'
      return json(inspectionFor(filename, filename === 'alpha.pdf' ? 2 : 1))
    }
    if (path === '/api/pdf/assemble') {
      const raw = request.postDataBuffer()?.toString('utf8') ?? ''
      const match = raw.match(/name="plan"\r?\n\r?\n([\s\S]*?)\r?\n--/)
      if (match?.[1]) {
        assemblePlans.push(JSON.parse(match[1]) as PdfAssemblePlan)
      }
      return route.fulfill({
        status: 200,
        contentType: 'application/pdf',
        headers: { 'Content-Disposition': 'attachment; filename="assembled.pdf"' },
        body: PDF_BYTES,
      })
    }
    if (path === '/api/pdf/extract/text') {
      return json({
        filename: 'alpha.pdf',
        pageCount: 2,
        totalChars: 24,
        truncated: false,
        pages: [
          { pageNumber: 1, text: 'Alpha page 1 text' },
          { pageNumber: 2, text: 'Alpha page 2 text' },
        ],
      })
    }
    if (path === '/api/pdf/extract/markdown') {
      return json({
        filename: 'alpha.pdf',
        pageCount: 2,
        totalChars: 38,
        truncated: false,
        markdown: '# alpha\n\n## Page 1\n\nAlpha page 1 text\n',
      })
    }
    if (path === '/api/pdf/extract/images') {
      imageCalls++
      if (options?.imageErrorFirst && imageCalls === 1) {
        return json({ message: 'No embedded raster images were found in this PDF.' }, 422)
      }
      return route.fulfill({
        status: 200,
        contentType: 'application/zip',
        headers: { 'Content-Disposition': 'attachment; filename="alpha-images.zip"' },
        body: Buffer.from('PK\x05\x06' + '\x00'.repeat(18), 'binary'),
      })
    }
    return json({ message: `Unexpected mock route ${path}` }, 404)
  })

  return { assemblePlans }
}

test('PDF Studio merges, reorders, rotates, scrubs metadata, and extracts content without horizontal overflow', async ({ page }) => {
  const { assemblePlans } = await setupPdfMocks(page, { imageErrorFirst: true })
  await page.goto('/')
  await switchMode(page, 'PDF Studio')

  await expect(page.getByRole('heading', { name: 'PDF Studio', exact: true })).toBeVisible()
  const overflowEmpty = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)
  expect(overflowEmpty).toBe(false)

  await page.locator('input[aria-label="Upload PDF files"]').setInputFiles([
    { name: 'alpha.pdf', mimeType: 'application/pdf', buffer: PDF_BYTES },
    { name: 'beta.pdf', mimeType: 'application/pdf', buffer: PDF_BYTES },
  ])

  await expect(page.getByRole('heading', { name: 'Loaded PDFs (2)', exact: true })).toBeVisible()
  const pageCards = page.getByRole('list', { name: 'PDF pages' }).getByRole('listitem')
  await expect(pageCards).toHaveCount(3)

  const overflowLoaded = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)
  expect(overflowLoaded).toBe(false)

  await page.getByRole('button', { name: 'Move page 3 earlier', exact: true }).click()
  await page.getByRole('button', { name: 'Rotate page 1 clockwise', exact: true }).click()
  await expect(pageCards.nth(0).getByText('90°')).toBeVisible()
  await page.getByRole('button', { name: 'Remove page 2', exact: true }).click()
  await expect(pageCards).toHaveCount(2)

  await page.getByRole('button', { name: 'Export assembled PDF', exact: true }).click()
  await expect.poll(() => assemblePlans.length).toBe(1)
  expect(assemblePlans[0].pages).toEqual([
    { fileIndex: 0, pageNumber: 1, rotation: 90 },
    { fileIndex: 0, pageNumber: 2, rotation: 0 },
  ])
  expect(assemblePlans[0].scrubMetadata).toBe(true)

  await page.getByRole('tab', { name: 'Inspect & Privacy', exact: true }).click()
  await expect(page.getByText('Experimental Paper')).toBeVisible()
  await expect(page.getByText('Ada Lovelace')).toBeVisible()
  await expect(page.getByText('Document contains 1 annotations or comments.')).toBeVisible()

  await page.getByLabel(/Strip annotations & comments/).check()
  await page.getByLabel('Diagonal watermark (optional)').fill('CONFIDENTIAL')
  await page.getByRole('button', { name: 'Apply & download PDF', exact: true }).click()
  await expect.poll(() => assemblePlans.length).toBe(2)
  expect(assemblePlans[1].stripAnnotations).toBe(true)
  expect(assemblePlans[1].watermarkText).toBe('CONFIDENTIAL')
  expect(assemblePlans[1].scrubMetadata).toBe(true)

  await page.getByRole('tab', { name: 'Extract Content', exact: true }).click()
  await page.getByRole('button', { name: 'Extract plain text', exact: true }).click()
  await expect(page.getByLabel('Extracted PDF content')).toHaveValue(/Alpha page 1 text/)

  await page.getByRole('button', { name: 'Extract Markdown', exact: true }).click()
  await expect(page.getByLabel('Extracted PDF content')).toHaveValue(/# alpha/)

  await page.getByRole('button', { name: /Download embedded images/ }).click()
  await expect(page.getByRole('alert')).toContainText('No embedded raster images were found in this PDF.')
  await page.getByRole('button', { name: 'Dismiss PDF Studio error', exact: true }).click()
  await expect(page.getByRole('alert')).toHaveCount(0)

  await page.getByRole('button', { name: /Download embedded images/ }).click()
  await expect(page.getByRole('alert')).toHaveCount(0)
})

test('switching between DOCX to LaTeX and PDF Studio preserves unsaved LaTeX edits', async ({ page }) => {
  await setupPdfMocks(page)
  await page.goto('/')
  await page.getByRole('button', { name: 'All documents', exact: true }).click()
  await page.getByRole('dialog', { name: 'All documents' }).getByRole('button', { name: 'Open draft.docx', exact: true }).click()

  const editor = page.getByRole('textbox', { name: 'LaTeX source editor' })
  await expect(editor).toBeVisible()
  await editor.click()
  await page.keyboard.press('End')
  await page.keyboard.type(' % UNSAVED_ECO_EDIT')
  await expect(page.getByText('Unsaved changes').first()).toBeVisible()

  await switchMode(page, 'PDF Studio')
  await expect(page.getByRole('heading', { name: 'PDF Studio', exact: true })).toBeVisible()

  await switchMode(page, 'DOCX → LaTeX')
  await expect(editor).toContainText('% UNSAVED_ECO_EDIT')
  await expect(page.getByText('Unsaved changes').first()).toBeVisible()
})

test('PDF Studio discards queued and pending inspections after removing a file or clearing all', async ({ page }) => {
  await setupPdfMocks(page)
  let releaseAlpha!: () => void
  let alphaCalls = 0
  let betaCalls = 0
  await page.route('**/api/pdf/inspect**', async route => {
    const body = route.request().postData() ?? ''
    if (body.includes('beta.pdf')) {
      betaCalls += 1
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(inspectionFor('beta.pdf', 1)) })
      return
    }
    alphaCalls += 1
    const gate = new Promise<void>(resolve => { releaseAlpha = resolve })
    await gate
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(inspectionFor('alpha.pdf', 2)) })
  })

  await page.goto('/')
  await switchMode(page, 'PDF Studio')

  await page.locator('input[aria-label="Upload PDF files"]').setInputFiles([
    { name: 'alpha.pdf', mimeType: 'application/pdf', buffer: PDF_BYTES },
    { name: 'beta.pdf', mimeType: 'application/pdf', buffer: PDF_BYTES },
  ])
  await expect.poll(() => alphaCalls).toBe(1)
  await expect(page.getByRole('heading', { name: 'Loaded PDFs (2)', exact: true })).toBeVisible()

  // Remove queued beta.pdf while alpha.pdf inspection is still pending.
  await page.getByRole('button', { name: 'Remove beta.pdf', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Loaded PDFs (1)', exact: true })).toBeVisible()

  releaseAlpha()
  const pageCards = page.getByRole('list', { name: 'PDF pages' }).getByRole('listitem')
  await expect(pageCards).toHaveCount(2)
  expect(betaCalls).toBe(0)

  // Now upload a fresh pair and Clear all while alpha inspection is in flight.
  await page.locator('input[aria-label="Upload PDF files"]').setInputFiles([
    { name: 'alpha.pdf', mimeType: 'application/pdf', buffer: PDF_BYTES },
    { name: 'beta.pdf', mimeType: 'application/pdf', buffer: PDF_BYTES },
  ])
  await expect.poll(() => alphaCalls).toBe(2)
  await page.getByRole('button', { name: 'Clear all', exact: true }).click()
  releaseAlpha()
  await expect(page.getByRole('list', { name: 'PDF pages' })).toHaveCount(0)
  expect(betaCalls).toBe(0)
})
