import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import type { ConversionReview, Job, ReadinessReport } from '../src/api'

const ID = '8e2c5256-a169-4ef0-9c71-2a52a8a5f6f0'
const SOURCE = '\\documentclass{article}\n\\begin{document}\nReady sample content.\n\\end{document}'

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

function readyReport(revision = 1): ReadinessReport {
  return {
    status: 'ready',
    headline: 'Ready to use',
    summary: 'Verified 1-page PDF (100% text match) with 1 automatic fix. Ready to download.',
    revision,
    pdfVerified: true,
    pageCount: 1,
    textCoverage: 1.0,
    autoFixes: [
      {
        code: 'duplicate-captions',
        title: 'Fixed duplicate figure/table numbers',
        detail: 'Removed repeated numbers from 1 caption so LaTeX numbers them once.',
        count: 1,
      },
    ],
    actions: [],
    handledAutomatically: [
      'Removed repeated numbers from 1 caption so LaTeX numbers them once.',
      'Word tracked changes were accepted automatically.',
    ],
  }
}

async function setup(
  page: Page,
  readiness: ReadinessReport = readyReport(1),
  options?: {
    progressive?: boolean
    holdCompletion?: boolean
    findings?: ConversionReview['findings']
    verifyOnPreviewPost?: ReadinessReport
  },
) {
  let polls = 0
  let releasePoll: (() => void) | null = null
  const pollGate = options?.holdCompletion
    ? new Promise<void>(resolve => {
        releasePoll = resolve
      })
    : null
  let notifyPollArrived: (() => void) | null = null
  const pollArrived = options?.holdCompletion
    ? new Promise<void>(resolve => {
        notifyPollArrived = resolve
      })
    : null
  const baseJob: Job = {
    id: ID,
    filename: 'paper.docx',
    template: 'article',
    status: 'CONVERTING',
    previewStatus: 'NOT_REQUESTED',
    message: 'Reading document structure and writing LaTeX.',
    previewMessage: '',
    createdAt: '2026-11-20T10:00:00Z',
    updatedAt: '2026-11-20T10:00:00Z',
    size: 1200,
    revision: 0,
  }
  const state = {
    jobs: [] as Job[],
    source: SOURCE,
    readiness,
    autoPrepares: [] as string[],
    previewPosts: 0,
    pollArrived,
    releasePoll: () => releasePoll?.(),
  }

  await page.context().route('**/api/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const json = (body: unknown, status = 200) => route.fulfill({ status, json: body })

    if (path === '/api/system') {
      return json({ pandoc: true, tectonic: true, templates: ['article', 'report'], maxUploadBytes: 20971520 })
    }
    if (path === '/api/jobs' && request.method() === 'POST') {
      const body = request.postDataBuffer()?.toString('utf8') ?? ''
      const auto = body.match(/name="autoPrepare"\r?\n\r?\n(true|false)/)?.[1] ?? ''
      state.autoPrepares.push(auto)
      const initialJob: Job = options?.progressive
        ? { ...baseJob }
        : {
            ...baseJob,
            status: 'COMPLETED',
            previewStatus: auto === 'true' ? 'READY' : 'NOT_REQUESTED',
            message: 'LaTeX and extracted images are ready.',
            previewMessage: auto === 'true' ? 'PDF preview is ready.' : '',
            revision: readiness.revision,
          }
      state.jobs = [initialJob]
      return json(initialJob, 202)
    }
    if (path === '/api/jobs') return json(state.jobs)
    if (path === '/api/library') return json({ items: state.jobs, page: 0, size: 20, total: state.jobs.length })
    if (path === `/api/jobs/${ID}`) {
      if (options?.progressive && state.jobs[0]) {
        polls++
        const current = state.jobs[0]
        if (polls === 1) {
          if (pollGate) {
            notifyPollArrived?.()
            await pollGate
          }
          state.jobs[0] = {
            ...current,
            status: 'COMPLETED',
            previewStatus: 'COMPILING',
            revision: state.readiness.revision,
            message: 'LaTeX is ready.',
            previewMessage: 'Building and verifying your PDF.',
          }
        } else {
          state.jobs[0] = {
            ...current,
            status: 'COMPLETED',
            previewStatus: 'READY',
            revision: state.readiness.revision,
            message: 'LaTeX is ready.',
            previewMessage: 'PDF preview is ready.',
            updatedAt: '2026-11-20T10:00:05Z',
          }
        }
      }
      return json(state.jobs[0])
    }
    if (path === `/api/jobs/${ID}/diagnostics`) {
      return json({
        revision: state.jobs[0]?.revision ?? 0,
        status: state.jobs[0]?.previewStatus ?? 'NOT_REQUESTED',
        logTruncated: false,
        diagnosticsTruncated: false,
        items: [],
      })
    }
    if (path === `/api/jobs/${ID}/review`) {
      const current = state.jobs[0]
      const review: ConversionReview = {
        elements: { headings: 1, tables: 0, equations: 0, images: 0, footnotes: 0, links: 0 },
        findings: options?.findings ?? [
          {
            code: 'captions',
            severity: 'warning',
            title: 'Captions already contain numbers',
            detail: 'Check duplicate numbering.',
          },
        ],
        compilerWarnings: [],
        scope: 'Text and structure comparison does not prove fidelity.',
        revision: current.revision,
        items: [],
        originalText: [],
        decisions: [],
        originalTruncated: false,
        decisionError: '',
        readiness: { ...state.readiness, revision: current.revision },
      }
      return json(review)
    }
    if (path === `/api/jobs/${ID}/preview` && request.method() === 'POST') {
      state.previewPosts++
      if (options?.verifyOnPreviewPost && state.jobs[0]) {
        state.readiness = options.verifyOnPreviewPost
        state.jobs[0] = { ...state.jobs[0], updatedAt: '2026-11-20T10:00:09Z' }
      }
      return json(state.jobs[0], 202)
    }
    if (path === `/api/jobs/${ID}/source`) {
      if (request.method() === 'PUT') {
        const body = request.postDataJSON() as { source: string; revision: number }
        state.source = body.source
        state.jobs[0] = {
          ...state.jobs[0],
          revision: body.revision + 1,
          previewStatus: 'NOT_REQUESTED',
          updatedAt: new Date().toISOString(),
        }
        return json(state.jobs[0])
      }
      return route.fulfill({ contentType: 'text/plain;charset=utf-8', body: state.source })
    }
    if (path === `/api/jobs/${ID}/preview` && request.method() === 'GET') {
      return route.fulfill({ status: 200, contentType: 'application/pdf', body: PDF_BYTES })
    }
    return json({ message: 'Unexpected request' }, 404)
  })

  await page.goto('/')
  return state
}

async function uploadPaper(page: Page) {
  await page.getByLabel('Upload Word document', { exact: true }).setInputFiles({
    name: 'paper.docx',
    mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    buffer: Buffer.from('synthetic docx upload'),
  })
  await page.getByRole('button', { name: 'Convert to LaTeX', exact: true }).click()
}

test('default auto-prepare lands on Preview with Ready to use banner and clears Review count', async ({ page }) => {
  const state = await setup(page, readyReport(1), { progressive: true })
  await expect(page.getByRole('checkbox', { name: /Auto-fix & verify PDF/ })).toBeChecked()
  await uploadPaper(page)

  expect(state.autoPrepares).toEqual(['true'])
  const previewTab = page.getByRole('tab', { name: 'Preview', exact: true })
  await expect(previewTab).toHaveAttribute('aria-selected', 'true')

  const banner = page.getByRole('region', { name: 'Document readiness', exact: true })
  await expect(banner.getByRole('heading', { name: 'Ready to use', exact: true })).toBeVisible()
  await expect(page.getByRole('img', { name: 'PDF preview, page 1 of 1', exact: true })).toBeVisible()
  await expect(page.locator('.pdf-navigation')).toContainText('PAGE 1 / 1')
  await expect(page.locator('.pdf-loading')).toHaveCount(0)
  await expect(banner).toContainText('Verified · 1 page')
  await expect(banner.getByRole('button', { name: 'Download PDF', exact: true })).toBeVisible()

  await banner.getByText('What was handled automatically (2)', { exact: true }).click()
  await expect(banner).toContainText('Removed repeated numbers from 1 caption')

  const reviewTab = page.getByRole('tab', { name: /^Review/ })
  await expect(reviewTab.locator('.ui-tab-count')).toHaveCount(0)
})

test('manual switch to Source during pending completion stays on Source and editing marks readiness stale', async ({ page }) => {
  const state = await setup(page, readyReport(1), { progressive: true, holdCompletion: true })
  await uploadPaper(page)

  await state.pollArrived
  const sourceTab = page.getByRole('tab', { name: 'Source', exact: true })
  await page.getByRole('tab', { name: /^Review/ }).click()
  await sourceTab.click()
  await expect(sourceTab).toHaveAttribute('aria-selected', 'true')

  state.releasePoll()
  const banner = page.getByRole('region', { name: 'Document readiness', exact: true })
  await expect(banner.getByRole('heading', { name: 'Ready to use', exact: true })).toBeVisible()
  await expect(sourceTab).toHaveAttribute('aria-selected', 'true')

  const editor = page.getByRole('textbox', { name: 'LaTeX source editor', exact: true })
  await editor.fill(SOURCE + '\n% manual user edit')

  await expect(banner.getByRole('heading', { name: 'Source edited — PDF check out of date', exact: true })).toBeVisible()
  await expect(editor).toContainText('% manual user edit')
  await expect(sourceTab).toHaveAttribute('aria-selected', 'true')
})

test('ready-with-notes and needs-attention render plain guidance with zero horizontal overflow', async ({ page }) => {
  const attention: ReadinessReport = {
    status: 'needs-attention',
    headline: 'Needs attention before use',
    summary: '1 item needs your attention in Word or Source before sharing this document.',
    revision: 0,
    pdfVerified: false,
    pageCount: null,
    textCoverage: null,
    autoFixes: [],
    actions: [
      {
        code: 'charts',
        severity: 'blocking',
        title: 'Word chart could not be converted',
        guidance: 'Export the chart as a PNG image in Word, insert it into your document, and convert again.',
      },
      {
        code: 'merged',
        severity: 'note',
        title: 'Check merged table cells',
        guidance: 'Glance at tables in the PDF preview to confirm rows and columns line up.',
      },
    ],
    handledAutomatically: [],
  }
  await setup(page, attention, { progressive: false })
  await uploadPaper(page)

  const banner = page.getByRole('region', { name: 'Document readiness', exact: true })
  await expect(banner.getByRole('heading', { name: 'Needs attention before use', exact: true })).toBeVisible()
  await expect(banner).toContainText('Word chart could not be converted')
  await expect(banner).toContainText('Export the chart as a PNG image in Word')
  await expect(banner).toContainText('Check merged table cells')
  await expect(page.getByRole('tab', { name: /^Review/ }).locator('.ui-tab-count')).toHaveText('2')

  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1)
})

test('unchecking Auto-fix & verify PDF sends autoPrepare=false and stays on Source', async ({ page }) => {
  const notes: ReadinessReport = {
    status: 'ready-with-notes',
    headline: 'LaTeX ready — build PDF to verify',
    summary: 'Conversion finished. 1 item is to check.',
    revision: 0,
    pdfVerified: false,
    pageCount: null,
    textCoverage: null,
    autoFixes: [],
    actions: [
      {
        code: 'pdf-unverified',
        severity: 'note',
        title: 'PDF has not been checked yet',
        guidance: 'Click Build PDF to verify the finished document, or download the LaTeX ZIP now.',
      },
    ],
    handledAutomatically: [],
  }
  const state = await setup(page, notes, { progressive: false })
  const toggle = page.getByRole('checkbox', { name: /Auto-fix & verify PDF/ })
  await toggle.uncheck()
  await expect(toggle).not.toBeChecked()
  await uploadPaper(page)

  expect(state.autoPrepares).toEqual(['false'])
  await expect(page.getByRole('tab', { name: 'Source', exact: true })).toHaveAttribute('aria-selected', 'true')
  const banner = page.getByRole('region', { name: 'Document readiness', exact: true })
  await expect(banner.getByRole('heading', { name: 'LaTeX ready — build PDF to verify', exact: true })).toBeVisible()
  await expect(banner.getByRole('button', { name: 'Build PDF', exact: true })).toBeVisible()
})

test('Review tab surfaces blocking readiness failure even with zero source or compiler warnings', async ({ page }) => {
  const failedReadiness: ReadinessReport = {
    status: 'needs-attention',
    headline: 'Needs attention before use',
    summary: '1 item needs your attention in Word or Source before sharing this document.',
    revision: 0,
    pdfVerified: false,
    pageCount: null,
    textCoverage: null,
    autoFixes: [],
    actions: [
      {
        code: 'pdf-failed',
        severity: 'blocking',
        title: 'PDF could not be built',
        guidance: 'Something in this document stopped PDF creation. You can still download the LaTeX ZIP or open Source to inspect it.',
      },
    ],
    handledAutomatically: [],
  }
  await setup(page, failedReadiness, { progressive: false, findings: [] })
  await uploadPaper(page)

  const reviewTab = page.getByRole('tab', { name: /^Review/ })
  await expect(reviewTab.locator('.ui-tab-count')).toHaveText('1')
  await reviewTab.click()

  const panel = page.getByRole('tabpanel', { name: /^Review/ })
  await expect(panel.getByRole('heading', { name: 'Action needed before sharing', exact: true })).toBeVisible()
  await expect(panel).toContainText('1 to review')
  await expect(panel).toContainText('PDF could not be built')
  await expect(panel).toContainText('Something in this document stopped PDF creation.')
  await expect(panel).not.toContainText('No issues detected')
  await expect(panel).not.toContainText('No common conversion risks detected')
})

test('Verify PDF verifies an existing unverified READY preview in place', async ({ page }) => {
  const unverifiedBuilt: ReadinessReport = {
    status: 'ready-with-notes',
    headline: 'PDF built — quick look suggested',
    summary: 'PDF preview is ready. 1 item is worth a quick glance.',
    revision: 1,
    pdfVerified: false,
    pageCount: null,
    textCoverage: null,
    autoFixes: [],
    actions: [
      {
        code: 'pdf-unverified-built',
        severity: 'note',
        title: 'Existing PDF has not been checked yet',
        guidance: 'Click Verify PDF to check this PDF against your Word document, or compare the preview manually.',
      },
    ],
    handledAutomatically: [],
  }
  const state = await setup(page, unverifiedBuilt, {
    progressive: false,
    verifyOnPreviewPost: readyReport(1),
  })
  await uploadPaper(page)

  const banner = page.getByRole('region', { name: 'Document readiness', exact: true })
  await expect(banner.getByRole('heading', { name: 'PDF built — quick look suggested', exact: true })).toBeVisible()
  await banner.getByRole('button', { name: 'Verify PDF', exact: true }).click()

  await expect(banner.getByRole('heading', { name: 'Ready to use', exact: true })).toBeVisible()
  await expect(banner).toContainText('Verified · 1 page')
  expect(state.previewPosts).toBe(1)
})
