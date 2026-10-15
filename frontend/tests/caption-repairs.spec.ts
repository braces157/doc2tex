import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import type { CaptionRepairPlan, Job, SavedVersion } from '../src/api'

const ID = 'd1662dd6-5025-4cc4-a56b-e7d8bc26ea95'
const BEFORE = '\\caption{Figure 1: Repeated observations}'
const AFTER = '\\caption{Repeated observations}'
const SOURCE = `\\begin{document}\nOrdinary prose: see Figure 1.\n\\begin{figure}\n${BEFORE}\n\\end{figure}\n\\end{document}`
const editor = (page: Page) => page.getByRole('textbox', { name: 'LaTeX source editor', exact: true })
const dialog = (page: Page) => page.getByRole('dialog', { name: 'Preview caption repairs', exact: true })

async function setup(page: Page) {
  const job: Job = { id: ID, filename: 'captions.docx', template: 'article', status: 'COMPLETED', previewStatus: 'NOT_REQUESTED',
    message: 'Ready', previewMessage: '', createdAt: '2026-10-16T00:00:00Z', updatedAt: '2026-10-16T00:00:00Z', size: 100, revision: 0 }
  const state = { job, source: SOURCE, writes: 0, failPreview: false, empty: false, conflict: false, versions: [SOURCE] }
  const plan = (): CaptionRepairPlan => ({ revision: state.job.revision, sourceHash: 'a'.repeat(64), warnings: ['PAGEREF requires manual review.'],
    proposals: state.empty ? [] : [{ id: 'b'.repeat(64), title: 'Remove repeated Figure 1 prefix', line: 4, start: SOURCE.indexOf(BEFORE), end: SOURCE.indexOf(BEFORE) + BEFORE.length, before: BEFORE, after: AFTER }] })
  const save = (source: string) => {
    state.source = source; state.versions.push(source); state.job = { ...state.job, revision: state.job.revision + 1 }
  }
  await page.context().route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    const json = (body: unknown, status = 200) => route.fulfill({ status, json: body })
    if (path === '/api/system') return json({ pandoc: true, tectonic: true, templates: ['article', 'report'], maxUploadBytes: 20971520 })
    if (path === '/api/jobs') return json([state.job])
    if (path === '/api/library') return json({ items: [state.job], page: 0, size: 20, total: 1 })
    if (path === `/api/jobs/${ID}`) return json(state.job)
    if (path.endsWith('/review')) return json({ elements: {}, findings: [{ code: 'captions', severity: 'warning', title: 'Numbered captions', detail: 'Review numbering.' }],
      compilerWarnings: [], scope: 'Review manually.', revision: state.job.revision, items: [], originalText: [], decisions: [], originalTruncated: false, decisionError: '' })
    if (path.endsWith('/diagnostics')) return json({ revision: null, status: 'NOT_REQUESTED', items: [], logTruncated: false, diagnosticsTruncated: false })
    if (path.endsWith('/caption-repairs')) {
      if (request.method() === 'GET') return state.failPreview ? json({ message: 'Preview temporarily unavailable.' }, 503) : json(plan())
      state.writes++
      const body = request.postDataJSON() as { revision: number; sourceHash: string; proposalIds: string[] }
      if (state.conflict || body.revision !== state.job.revision) return json({ message: 'The source changed in another window. Preview repairs again.' }, 409)
      expect(body.sourceHash).toBe('a'.repeat(64)); expect(body.proposalIds).toEqual(['b'.repeat(64)])
      save(SOURCE.replace(BEFORE, AFTER)); return json({ job: state.job, source: state.source })
    }
    if (path.endsWith('/versions')) {
      const items = state.versions.map<SavedVersion>((source, revision) => ({ revision, bytes: Buffer.byteLength(source), createdAt: '2026-10-16T00:00:00Z', origin: revision === 0 ? 'converted' : 'saved', restoredFrom: -1 })).reverse()
      return json({ items, currentRevision: state.job.revision, firstRevision: 0, legacyHistoryUnavailable: false, maxVersions: 100, maxBytes: 104857600, bytesUsed: 1000 })
    }
    const version = path.match(/\/versions\/(\d+)\/(source|restore)$/)
    if (version) {
      const source = state.versions[Number(version[1])]
      if (version[2] === 'source') return route.fulfill({ contentType: 'text/plain', body: source })
      expect((request.postDataJSON() as { revision: number }).revision).toBe(state.job.revision)
      save(source); return json({ job: state.job, source })
    }
    if (path.endsWith('/source')) return route.fulfill({ contentType: 'text/plain', body: state.source })
    return json({ message: 'Unexpected request' }, 404)
  })
  await page.goto('/')
  await page.getByRole('button', { name: 'All documents', exact: true }).click()
  await page.getByRole('dialog', { name: 'All documents' }).getByRole('button', { name: 'Open captions.docx', exact: true }).click()
  await expect(editor(page)).toHaveText(SOURCE, { useInnerText: true })
  return state
}

async function open(page: Page) {
  await page.getByRole('tab', { name: /^Review/ }).click()
  await page.getByRole('button', { name: 'Preview caption repairs', exact: true }).click()
}

test('exact diff can be cancelled or accepted and recovered through saved versions', async ({ page }, testInfo) => {
  const state = await setup(page); await open(page)
  await expect(dialog(page).getByLabel('Source before repair at line 4')).toHaveText(BEFORE)
  await expect(dialog(page).getByLabel('Source after repair at line 4')).toHaveText(AFTER)
  const checkbox = dialog(page).getByRole('checkbox')
  await checkbox.uncheck(); await expect(dialog(page).getByRole('button', { name: 'Apply selected repairs', exact: true })).toBeDisabled()
  await checkbox.check(); await dialog(page).screenshot({ path: testInfo.outputPath('caption-source-diff.png') })
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1)
  await dialog(page).getByRole('button', { name: 'Cancel', exact: true }).click()
  expect(state.writes).toBe(0); expect(state.source).toBe(SOURCE)
  await page.getByRole('tab', { name: 'Source', exact: true }).click(); await expect(editor(page)).toHaveText(SOURCE, { useInnerText: true })
  await open(page); await dialog(page).getByRole('button', { name: 'Apply selected repairs', exact: true }).click()
  await expect(dialog(page)).toBeHidden(); await expect(editor(page)).toHaveText(SOURCE.replace(BEFORE, AFTER), { useInnerText: true })
  expect(state.job.revision).toBe(1); expect(state.versions[0]).toBe(SOURCE)
  await page.getByRole('button', { name: 'Saved versions', exact: true }).click()
  const history = page.getByRole('dialog', { name: 'Saved versions', exact: true })
  await history.getByRole('button', { name: /^Version 0(?: |$)/ }).click()
  await expect(history.getByRole('textbox', { name: 'Saved version source', exact: true })).toHaveValue(SOURCE)
  await history.getByRole('button', { name: 'Restore version 0', exact: true }).click()
  await expect(history).toBeHidden(); await expect(editor(page)).toHaveText(SOURCE, { useInnerText: true })
  expect(state.job.revision).toBe(2)
})

test('dirty source disables repairs and a stale apply preserves the editor and other saved text', async ({ page }) => {
  const state = await setup(page)
  await editor(page).fill(SOURCE + '\n% local draft')
  await page.getByRole('tab', { name: /^Review/ }).click()
  await expect(page.getByRole('button', { name: 'Preview caption repairs', exact: true })).toBeDisabled()
  await page.getByRole('tab', { name: 'Source', exact: true }).click(); await editor(page).fill(SOURCE)
  await open(page); await expect(dialog(page).getByRole('checkbox')).toBeChecked()
  state.conflict = true; state.source = SOURCE + '\n% other window'
  await dialog(page).getByRole('button', { name: 'Apply selected repairs', exact: true }).click()
  await expect(dialog(page).getByRole('alert')).toContainText('source changed in another window')
  await dialog(page).getByRole('button', { name: 'Cancel', exact: true }).click()
  await page.getByRole('tab', { name: 'Source', exact: true }).click(); await expect(editor(page)).toHaveText(SOURCE, { useInnerText: true })
  expect(state.source).toBe(SOURCE + '\n% other window'); expect(state.job.revision).toBe(0)
})

test('preview failure can retry and unsupported captions remain a manual review state', async ({ page }) => {
  const state = await setup(page); state.failPreview = true; await open(page)
  await expect(dialog(page).getByRole('alert')).toContainText('Preview temporarily unavailable')
  state.failPreview = false; state.empty = true
  await dialog(page).getByRole('button', { name: 'Preview again', exact: true }).click()
  await expect(dialog(page)).toContainText('No safe caption-numbering repairs were found')
  await expect(dialog(page).getByRole('button', { name: 'Apply selected repairs', exact: true })).toBeDisabled()
  await dialog(page).getByText('Items requiring manual review', { exact: true }).click()
  await expect(dialog(page)).toContainText('PAGEREF requires manual review')
  expect(state.writes).toBe(0)
})
