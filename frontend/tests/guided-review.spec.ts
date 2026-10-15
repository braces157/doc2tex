import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import type { ConversionReview, Job, ReviewDecision, ReviewItem } from '../src/api'

const ID = '670f136b-5e6b-4f2c-8e03-89a1646f5567'
const CAPTION = 'Figure 1: Distinctive caption evidence'
const SOURCE = `\\begin{document}\n\n\\caption{${CAPTION}}\n\n\\begin{longtable}{ll}\nDistinctive merged table anchor & value \\\\\n\\end{longtable}\n\n\\end{document}`
const baseItems: ReviewItem[] = [
  { id: 'a'.repeat(64), code: 'captions', title: 'Caption already contains a number', part: 'word/document.xml', location: 'paragraph 1', excerpt: CAPTION, excerptTruncated: false,
    mapping: { status: 'matched', line: 3, reason: 'Unique original anchor and exact source text match; compare structure manually.', sourceExcerpt: `\\caption{${CAPTION}}` }, fingerprint: 'e'.repeat(64) },
  { id: 'b'.repeat(64), code: 'merged', title: 'Merged table cells', part: 'word/document.xml', location: 'table 1', excerpt: 'Distinctive merged table anchor | value', excerptTruncated: false,
    mapping: { status: 'matched', line: 6, reason: 'Unique original anchor inside a verified table environment.', sourceExcerpt: 'Distinctive merged table anchor & value' }, fingerprint: 'f'.repeat(64) },
  { id: 'c'.repeat(64), code: 'headers', title: 'Header or footer text', part: 'word/header1.xml', location: 'paragraph 1', excerpt: 'HEADER_CONTEXT_NOT_BODY · Essential original header', excerptTruncated: false,
    mapping: { status: 'unavailable', line: null, reason: 'Word headers and footers are omitted from the converted body; no source location is assumed.', sourceExcerpt: '' }, fingerprint: '1'.repeat(64) },
  { id: 'd'.repeat(64), code: 'captions', title: 'Repeated caption', part: 'word/document.xml', location: 'paragraph 8', excerpt: 'Figure 2 Repeated observations <script>window.reviewInjected=true</script>', excerptTruncated: false,
    mapping: { status: 'unavailable', line: null, reason: 'This original text is repeated; its location is ambiguous.', sourceExcerpt: '' }, fingerprint: '2'.repeat(64) },
]

async function setup(page: Page) {
  const job: Job = { id: ID, filename: 'research-complex.docx', template: 'article', status: 'COMPLETED', previewStatus: 'NOT_REQUESTED',
    message: 'Ready', previewMessage: '', createdAt: '2026-10-16T00:00:00Z', updatedAt: '2026-10-16T00:00:00Z', size: 100, revision: 0 }
  const state = { job, source: SOURCE, stored: [] as ReviewDecision[], failDecision: false, gate: null as Promise<void> | null, gateStarted: null as (() => void) | null }
  const report = (): ConversionReview => {
    const items = baseItems.map(item => {
      const changed = item.code === 'captions' && item.mapping.status === 'matched' && state.source.includes('Changed caption')
      return { ...item, mapping: changed ? { status: 'unavailable' as const, line: null, reason: 'No unique exact text match is available.', sourceExcerpt: '' } : item.mapping,
        fingerprint: changed ? '9'.repeat(64) : item.mapping.status === 'unavailable' ? String(state.job.revision % 10).repeat(64) : item.fingerprint }
    })
    return { elements: { tables: 1, images: 1 }, findings: [{ code: 'captions', severity: 'warning', title: 'Captions already contain numbers', detail: 'Check duplicate numbering.' },
      { code: 'merged', severity: 'warning', title: 'Merged table cells', detail: 'Check table structure.' }, { code: 'headers', severity: 'warning', title: 'Headers contain text', detail: 'Read omitted header text.' }],
    compilerWarnings: [], scope: 'Text and structure comparison does not prove fidelity.', revision: state.job.revision, items,
    originalText: items.map(item => ({ part: item.part, location: item.location, text: item.excerpt, truncated: false })), originalTruncated: false, decisionError: '',
    decisions: state.stored.map(decision => { const item = items.find(item => item.id === decision.findingId)!; const applicable = item.fingerprint === decision.fingerprint
      return { ...decision, applicable, carried: applicable && decision.revision !== state.job.revision, applicableRevision: state.job.revision,
        reason: applicable ? 'Original finding and mapped source evidence are unchanged.' : 'Relevant evidence changed; review again.' } }) }
  }
  await page.context().route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    const json = (body: unknown, status = 200) => route.fulfill({ status, json: body })
    if (path === '/api/system') return json({ pandoc: true, tectonic: true, templates: ['article', 'report'], maxUploadBytes: 20971520 })
    if (path === '/api/jobs') return json([state.job])
    if (path === '/api/library') return json({ items: [state.job], page: 0, size: 20, total: 1 })
    if (path === `/api/jobs/${ID}`) return json(state.job)
    if (path.endsWith('/diagnostics')) return json({ revision: null, status: 'NOT_REQUESTED', items: [], logTruncated: false, diagnosticsTruncated: false })
    if (path.endsWith('/review')) { const data = report(), gate = state.gate; state.gate = null; if (gate) { state.gateStarted?.(); await gate } return json(data) }
    if (path.includes('/review/decisions/')) {
      if (state.failDecision) return json({ message: 'Review storage temporarily unavailable.' }, 503)
      const body = request.postDataJSON() as { revision: number; status: ReviewDecision['status'] | 'unreviewed'; fingerprint: string }
      const id = path.split('/').at(-1)!, item = report().items!.find(item => item.id === id)!
      if (body.revision !== state.job.revision || body.fingerprint !== item.fingerprint) return json({ message: 'Finding evidence changed.' }, 409)
      state.stored = state.stored.filter(decision => decision.findingId !== id)
      if (body.status !== 'unreviewed') state.stored.push({ findingId: id, status: body.status, revision: body.revision, fingerprint: body.fingerprint,
        updatedAt: new Date().toISOString(), applicable: true, carried: false, applicableRevision: body.revision, reason: 'Reviewed against saved source.' })
      return json(report())
    }
    if (path.endsWith('/source')) {
      if (request.method() === 'PUT') {
        const body = request.postDataJSON() as { revision: number; source: string }
        if (body.revision !== state.job.revision) return json({ message: 'Source changed' }, 409)
        state.source = body.source; state.job = { ...state.job, revision: state.job.revision + 1, updatedAt: new Date().toISOString() }; return json(state.job)
      }
      return route.fulfill({ contentType: 'text/plain', body: state.source })
    }
    return json({ message: 'Unexpected request' }, 404)
  })
  await page.goto('/')
  await page.getByRole('button', { name: 'All documents', exact: true }).click()
  await page.getByRole('dialog', { name: 'All documents' }).getByRole('button', { name: 'Open research-complex.docx', exact: true }).click()
  await expect(page.getByRole('textbox', { name: 'LaTeX source editor', exact: true })).toBeVisible()
  return state
}
const caption = (page: Page) => page.getByRole('article', { name: 'Caption already contains a number, paragraph 1', exact: true })
const header = (page: Page) => page.getByRole('article', { name: 'Header or footer text, paragraph 1', exact: true })
const source = (page: Page) => page.getByRole('textbox', { name: 'LaTeX source editor', exact: true })

test('original excerpts compare beside source on desktop and stack on mobile with safe location links', async ({ page, isMobile }, testInfo) => {
  await setup(page); await page.getByRole('tab', { name: /^Review/ }).click()
  await expect(header(page)).toContainText('HEADER_CONTEXT_NOT_BODY')
  await expect(header(page).getByRole('button', { name: /Go to source/ })).toHaveCount(0)
  const repeated = page.getByRole('article', { name: 'Repeated caption, paragraph 8', exact: true })
  await expect(repeated).toContainText('ambiguous'); await expect(repeated.getByRole('button', { name: /Go to source/ })).toHaveCount(0)
  expect(await page.evaluate(() => 'reviewInjected' in window)).toBe(false)
  await caption(page).getByRole('button', { name: 'Go to source line 3', exact: true }).click()
  const original = page.getByRole('complementary', { name: 'Original Word text comparison' })
  await expect(original).toContainText(CAPTION); await expect(source(page)).toBeFocused()
  await expect(page.getByText('Line 3 of 9', { exact: true })).toBeVisible()
  const originalBox = await original.boundingBox(), editorBox = await page.locator('.ui-review-editor').boundingBox()
  expect(originalBox).not.toBeNull(); expect(editorBox).not.toBeNull()
  if (isMobile) expect(originalBox!.y).toBeLessThan(editorBox!.y)
  else { expect(originalBox!.x).toBeLessThan(editorBox!.x); expect(Math.abs(originalBox!.y - editorBox!.y)).toBeLessThan(3) }
  await page.locator('.ui-review-comparison').screenshot({ path: testInfo.outputPath('original-source-comparison.png') })
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1)
  await original.getByRole('button', { name: 'Close original text comparison', exact: true }).click(); await expect(original).toHaveCount(0)
})

test('review decisions survive reload, retain warnings, carry unchanged evidence and become stale after edits', async ({ page }) => {
  const state = await setup(page); await page.getByRole('tab', { name: /^Review/ }).click()
  await caption(page).getByRole('button', { name: 'Mark reviewed', exact: true }).click()
  await expect(caption(page)).toContainText('Reviewed · revision 0')
  await header(page).getByRole('button', { name: 'Needs work', exact: true }).click(); await expect(header(page)).toContainText('Needs work · revision 0')
  await page.reload(); await page.getByRole('tab', { name: /^Review/ }).click()
  await expect(caption(page)).toContainText('Reviewed · revision 0'); await expect(page.getByText('Captions already contain numbers', { exact: true })).toBeVisible()
  await page.getByRole('tab', { name: 'Source', exact: true }).click(); await source(page).fill(SOURCE + '\n% unrelated change')
  await page.getByRole('tab', { name: /^Review/ }).click(); await expect(caption(page).getByRole('button', { name: 'Mark reviewed', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: 'Save changes', exact: true }).click()
  await expect(caption(page)).toContainText('carried from revision 0'); await expect(header(page)).toContainText('Review again')
  await page.getByRole('tab', { name: 'Source', exact: true }).click(); await source(page).fill(SOURCE.replace(CAPTION, 'Figure 1: Changed caption text'))
  await page.getByRole('button', { name: 'Save changes', exact: true }).click(); await page.getByRole('tab', { name: /^Review/ }).click()
  await expect(caption(page)).toContainText('Review again'); expect(state.stored).toHaveLength(2)
  await caption(page).getByRole('button', { name: 'Clear decision', exact: true }).click(); await expect(caption(page)).toContainText('Not reviewed')
  await expect(page.getByText('Captions already contain numbers', { exact: true })).toBeVisible()
})

test('failed decision writes retain source and warnings and offer review reload', async ({ page }) => {
  const state = await setup(page); state.failDecision = true
  await page.getByRole('tab', { name: /^Review/ }).click(); await caption(page).getByRole('button', { name: 'Mark reviewed', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Review storage temporarily unavailable')
  await expect(caption(page)).toContainText('Not reviewed'); expect(state.stored).toHaveLength(0); expect(state.source).toBe(SOURCE)
  state.failDecision = false; await page.getByRole('button', { name: 'Reload conversion review', exact: true }).click()
  await caption(page).getByRole('button', { name: 'Mark reviewed', exact: true }).click(); await expect(caption(page)).toContainText('Reviewed · revision 0')
})

test('delayed review data cannot replace a newer saved revision or its decisions', async ({ page }) => {
  // Model a transport that delivers a response despite cancellation, exercising late-result guards.
  await page.addInitScript(() => {
    const fetch = window.fetch.bind(window)
    window.fetch = async (input, options) => {
      if (typeof input !== 'string' || !/\/api\/jobs\/[^/]+\/review$/.test(input)) return fetch(input, options)
      const response = await fetch(input, { ...options, signal: undefined })
      const json = response.json.bind(response)
      response.json = async () => {
        const data = await json() as ConversionReview
        if (data.revision === 1) {
          // A timer follows the body/API promise microtasks; two render turns follow queued React work.
          setTimeout(() => requestAnimationFrame(() => requestAnimationFrame(() => {
            document.documentElement.dataset.lateReviewConsumed = '1'
          })), 0)
        }
        return data
      }
      return response
    }
  })
  const state = await setup(page); let release!: () => void
  await page.getByRole('tab', { name: /^Review/ }).click(); await expect(caption(page)).toBeVisible()
  await page.getByRole('tab', { name: 'Source', exact: true }).click()
  const started = new Promise<void>(resolve => { state.gateStarted = resolve })
  state.gate = new Promise<void>(resolve => { release = resolve })
  await source(page).fill(SOURCE + '\n% first revision'); await page.getByRole('button', { name: 'Save changes', exact: true }).click()
  await started
  await expect(page.getByRole('button', { name: 'Save changes', exact: true })).toBeDisabled()
  await source(page).fill(SOURCE + '\n% second revision'); await page.getByRole('button', { name: 'Save changes', exact: true }).click()
  await page.getByRole('tab', { name: /^Review/ }).click(); await expect(page.getByText(/Saved source revision 2\./)).toBeVisible()
  const lateResponse = page.waitForResponse(async response => new URL(response.url()).pathname === `/api/jobs/${ID}/review` && (await response.json() as ConversionReview).revision === 1)
  release(); expect((await (await lateResponse).json() as ConversionReview).revision).toBe(1)
  await expect(page.locator('html')).toHaveAttribute('data-late-review-consumed', '1')
  await expect(page.getByText(/Saved source revision 2\./)).toBeVisible()
  await caption(page).getByRole('button', { name: 'Mark reviewed', exact: true }).click(); await expect(caption(page)).toContainText('Reviewed · revision 2')
  expect(state.stored[0].revision).toBe(2)
})
