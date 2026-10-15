import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import type { BibliographyEntry, BibliographyPlan, BibliographyReport, Job, SavedVersion } from '../src/api'

const ID = '218554a0-530f-4524-8c0d-7c5fc45aa97f'
const SOURCE = '\\documentclass{article}\n\\begin{document}\nBody text.\n\\end{document}'
const BIB = '@book{doe2024,title={Synthetic Résumé}}\n@article{ivanov2025,title={Synthetic Проверка}}\n'
const REPLACEMENT = '@book{replacement,title={Replacement library}}\n'
const ENTRIES: BibliographyEntry[] = [
  { key: 'doe2024', type: 'book', title: 'Synthetic Résumé', author: 'Jane Doe', year: '2024', line: 1 },
  { key: 'ivanov2025', type: 'article', title: 'Synthetic Проверка', author: 'Ivan Ivanov', year: '2025', line: 2 },
]
const SECTION = '\\bibliographystyle{plain}\n\\bibliography{bibliography/references}\n'
const editor = (page: Page) => page.getByRole('textbox', { name: 'LaTeX source editor', exact: true })
const panel = (page: Page) => page.getByRole('region', { name: 'Reference library', exact: true })
const setupDialog = (page: Page) => page.getByRole('dialog', { name: 'Preview bibliography setup', exact: true })
interface Snapshot { source: string; bib: string | null; filename: string; origin: SavedVersion['origin']; restoredFrom: number }

async function setup(page: Page) {
  const job: Job = { id: ID, filename: 'paper.docx', template: 'article', status: 'COMPLETED', previewStatus: 'NOT_REQUESTED', message: 'Ready', previewMessage: '',
    createdAt: '2026-10-16T00:00:00Z', updatedAt: '2026-10-16T00:00:00Z', size: 100, revision: 0 }
  const state = { job, versions: [{ source: SOURCE, bib: null, filename: '', origin: 'converted', restoredFrom: -1 }] as Snapshot[],
    imports: 0, failImport: false, conflict: false, setupConflict: false, failLoad: false, failSetup: false, failDownload: false, compiled: null as Snapshot | null,
    uploadGate: null as Promise<void> | null, uploadStarted: null as (() => void) | null,
    getGate: null as Promise<void> | null, getStarted: null as (() => void) | null }
  const current = () => state.versions[state.job.revision]
  const save = (source: string, bib = current().bib, filename = current().filename, origin: SavedVersion['origin'] = 'saved', restoredFrom = -1) => {
    state.versions.push({ source, bib, filename, origin, restoredFrom }); state.job = { ...state.job, revision: state.job.revision + 1, previewStatus: 'NOT_REQUESTED' }
  }
  const report = (): BibliographyReport => ({ revision: state.job.revision, filename: current().filename, bytes: Buffer.byteLength(current().bib ?? ''), sha256: 'a'.repeat(64),
    entries: current().bib === null ? [] : current().bib === REPLACEMENT ? [{ key: 'replacement', type: 'book', title: 'Replacement library', author: '', year: '', line: 1 }] : ENTRIES,
    unknownKeys: current().source.includes('\\cite{unknown_key}') ? ['unknown_key'] : [], warnings: [] })
  const plan = (): BibliographyPlan => ({ revision: state.job.revision, sourceHash: 'b'.repeat(64), warnings: ['Review the plain BibTeX source changes.'],
    changes: current().source.includes('\\bibliography{') ? [] : [
      { line: 2, title: 'Load the bibliography font', before: '\\begin{document}', after: '\\usepackage{fontspec}\n\\begin{document}' },
      { line: 4, title: 'Add the bibliography section', before: '\\end{document}', after: SECTION + '\\end{document}' },
    ] })
  await page.context().route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    const json = (body: unknown, status = 200) => route.fulfill({ status, json: body })
    if (path === '/api/system') return json({ pandoc: true, tectonic: true, templates: ['article', 'report'], maxUploadBytes: 20971520 })
    if (path === '/api/jobs') return json([state.job])
    if (path === '/api/library') return json({ items: [state.job], page: 0, size: 20, total: 1 })
    if (path === `/api/jobs/${ID}`) return json(state.job)
    if (path.endsWith('/review')) return json({ elements: {}, findings: [], compilerWarnings: [], scope: 'Review manually.', revision: state.job.revision })
    if (path.endsWith('/diagnostics')) return json({ revision: state.job.revision, status: state.job.previewStatus, items: [], logTruncated: false, diagnosticsTruncated: false })
    const version = path.match(/\/versions\/(\d+)\/(source|restore|bibliography)$/)
    if (version) {
      const revision = Number(version[1]), selected = state.versions[revision]
      if (version[2] === 'source') return route.fulfill({ contentType: 'text/plain', body: selected.source })
      if (version[2] === 'bibliography') return state.failDownload ? json({ message: 'Version bibliography temporarily unavailable.' }, 503) : route.fulfill({ contentType: 'application/x-bibtex', headers: { 'Content-Disposition': 'attachment; filename=references.bib' }, body: selected.bib ?? '' })
      expect((request.postDataJSON() as { revision: number }).revision).toBe(state.job.revision)
      save(selected.source, selected.bib, selected.filename, 'restored', revision); return json({ job: state.job, source: selected.source })
    }
    if (path.endsWith('/versions')) {
      const items = state.versions.map<SavedVersion>((snapshot, revision) => ({ revision, createdAt: '2026-10-16T00:00:00Z', bytes: Buffer.byteLength(snapshot.source), origin: snapshot.origin,
        restoredFrom: snapshot.restoredFrom, bibliographyFilename: snapshot.filename, assetBytes: snapshot.bib === null ? 0 : Buffer.byteLength(snapshot.bib) + 620000 })).reverse()
      return json({ items, currentRevision: state.job.revision, firstRevision: 0, legacyHistoryUnavailable: false, maxVersions: 100, maxBytes: 104857600, bytesUsed: 1000000 })
    }
    if (path === `/api/jobs/${ID}/source`) {
      if (request.method() === 'PUT') { const body = request.postDataJSON() as { source: string; revision: number }; expect(body.revision).toBe(state.job.revision); save(body.source); return json(state.job) }
      return route.fulfill({ contentType: 'text/plain', body: current().source })
    }
    if (path.endsWith('/bibliography/setup')) {
      if (request.method() === 'GET') return state.failSetup ? json({ message: 'Setup temporarily unavailable.' }, 503) : json(plan())
      if (state.setupConflict) return json({ message: 'The source changed. Preview bibliography setup again.' }, 409)
      const body = request.postDataJSON() as { revision: number; sourceHash: string }; expect(body.revision).toBe(state.job.revision); expect(body.sourceHash).toBe('b'.repeat(64))
      save(current().source.replace('\\begin{document}', '\\usepackage{fontspec}\n\\begin{document}').replace('\\end{document}', SECTION + '\\end{document}'))
      return json({ job: state.job, source: current().source })
    }
    if (path.endsWith('/bibliography/file')) return route.fulfill({ contentType: 'application/x-bibtex', headers: { 'Content-Disposition': 'attachment; filename=references.bib' }, body: current().bib ?? '' })
    if (path.endsWith('/bibliography')) {
      if (request.method() === 'GET') {
        if (state.failLoad) return json({ message: 'Library temporarily unavailable.' }, 503)
        const data = report(), gate = state.getGate
        if (gate && data.revision === 2) { state.getGate = null; state.getStarted?.(); await gate }
        return json(data)
      }
      state.imports++
      const gate = state.uploadGate; state.uploadGate = null; if (gate) { state.uploadStarted?.(); await gate }
      if (state.failImport) return json({ message: "Duplicate citation key 'doe2024'. Rename it before importing." }, 400)
      if (state.conflict) return json({ message: 'The source or bibliography changed. Reopen this document before importing.' }, 409)
      const body = request.postData()!, revision = Number(body.match(/name="revision"\r\n\r\n(\d+)/)![1]); expect(revision).toBe(state.job.revision)
      const replacement = body.includes('Replacement library'); save(current().source, replacement ? REPLACEMENT : BIB, replacement ? 'replacement.bib' : 'original.bib')
      return json({ job: state.job, source: current().source })
    }
    if (path.endsWith('/preview') && request.method() === 'POST') { state.compiled = { ...current() }; state.job = { ...state.job, previewStatus: 'QUEUED' }; return json(state.job, 202) }
    return json({ message: 'Unexpected request' }, 404)
  })
  await page.goto('/'); await page.getByRole('button', { name: 'All documents', exact: true }).click()
  await page.getByRole('dialog', { name: 'All documents' }).getByRole('button', { name: 'Open paper.docx', exact: true }).click()
  await expect(editor(page)).toHaveText(SOURCE, { useInnerText: true })
  await page.getByText('Bibliography', { exact: true }).click(); await expect(panel(page)).toContainText('No bibliography attached.')
  return state
}
async function upload(page: Page, replacement = false) {
  await panel(page).getByLabel('Import BibTeX bibliography', { exact: true }).setInputFiles({ name: replacement ? 'replacement.bib' : 'original.bib', mimeType: 'application/x-bibtex', buffer: Buffer.from(replacement ? REPLACEMENT : BIB, 'utf8') })
}
async function setupSource(page: Page) {
  await panel(page).getByRole('button', { name: 'Preview bibliography setup', exact: true }).click()
  await setupDialog(page).getByRole('button', { name: 'Apply bibliography setup', exact: true }).click()
  await expect(setupDialog(page)).toBeHidden()
}
async function selectVersion(page: Page, revision: number) {
  const history = page.getByRole('dialog', { name: 'Saved versions', exact: true })
  await history.getByRole('button', { name: new RegExp(`^Version ${revision}(?: |$)`) }).click()
  return history
}

test('import, exact setup preview, undoable insertion, Unicode search and build use one saved bibliography', async ({ page }, testInfo) => {
  const state = await setup(page); await upload(page); await expect(panel(page)).toContainText('original.bib')
  await panel(page).getByRole('button', { name: 'Preview bibliography setup', exact: true }).click()
  await expect(setupDialog(page).getByLabel('Before bibliography change 2')).toHaveText('\\end{document}')
  await expect(setupDialog(page).getByLabel('After bibliography change 2')).toContainText('\\bibliography{bibliography/references}')
  await setupDialog(page).screenshot({ path: testInfo.outputPath('bibliography-setup-diff.png') })
  await setupDialog(page).getByRole('button', { name: 'Cancel', exact: true }).click(); await expect(editor(page)).toHaveText(SOURCE, { useInnerText: true })
  expect(state.job.revision).toBe(1); await setupSource(page)
  const draft = state.versions[state.job.revision].source.replace('Body text.', 'Body text. Keep local notes.')
  await editor(page).fill(draft)
  const goToBody = async () => { await page.getByRole('spinbutton', { name: 'Go to line', exact: true }).fill(String(draft.split('\n').findIndex(line => line.startsWith('Body text.')) + 1)); await page.getByRole('button', { name: 'Go', exact: true }).click() }
  await goToBody(); await panel(page).getByRole('button', { name: 'Insert citation doe2024', exact: true }).click()
  await expect(editor(page)).toContainText('\\cite{doe2024}'); await expect(editor(page)).toContainText('Keep local notes.')
  await page.getByRole('button', { name: 'Undo', exact: true }).click(); await expect(editor(page)).toHaveText(draft, { useInnerText: true })
  await panel(page).getByLabel('Search citation keys', { exact: true }).fill('Проверка')
  await expect(panel(page).getByRole('button', { name: 'Insert citation doe2024', exact: true })).toHaveCount(0)
  await goToBody(); await panel(page).getByRole('button', { name: 'Insert citation ivanov2025', exact: true }).click()
  await panel(page).getByLabel('Search citation keys', { exact: true }).fill(''); await panel(page).getByRole('button', { name: 'Insert citation doe2024', exact: true }).click()
  await expect(editor(page)).toContainText('\\cite{doe2024}'); await expect(editor(page)).toContainText('\\cite{ivanov2025}')
  await page.getByRole('button', { name: 'Save changes', exact: true }).click(); await expect(panel(page)).toContainText('Saved source revision 3.')
  await panel(page).screenshot({ path: testInfo.outputPath('bibliography-key-search.png') })
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1)
  await page.getByRole('tab', { name: 'Preview', exact: true }).click(); await page.getByRole('button', { name: 'Build PDF preview', exact: true }).click()
  await expect(page.getByText('Setting your words in place…', { exact: true })).toBeVisible()
  expect(state.compiled?.bib).toBe(BIB); expect(state.compiled?.source).toContain('\\cite{doe2024}'); expect(state.compiled?.source).toContain('Keep local notes.')
})

test('replacing and restoring versions restores matching bibliography and preserves historical downloads', async ({ page }, testInfo) => {
  const state = await setup(page); await upload(page); await expect(panel(page)).toContainText('original.bib'); await setupSource(page)
  await upload(page, true); await expect(panel(page)).toContainText('replacement.bib'); expect(state.job.revision).toBe(3)
  await page.getByRole('button', { name: 'Saved versions', exact: true }).click(); const history = await selectVersion(page, 3)
  await expect(history).toContainText('This version includes replacement.bib')
  await history.screenshot({ path: testInfo.outputPath('bibliography-version-assets.png') })
  state.failDownload = true; await history.getByRole('button', { name: 'Download version bibliography', exact: true }).click()
  await expect(history.getByRole('alert')).toContainText('Version bibliography temporarily unavailable'); state.failDownload = false
  const downloading = page.waitForEvent('download'); await history.getByRole('button', { name: 'Download version bibliography', exact: true }).click()
  expect(await readFile((await (await downloading).path())!, 'utf8')).toBe(REPLACEMENT)
  await selectVersion(page, 2); await history.getByRole('button', { name: 'Restore version 2', exact: true }).click(); await expect(history).toBeHidden()
  await expect(panel(page)).toContainText('original.bib'); await expect(panel(page).getByRole('button', { name: 'Insert citation doe2024', exact: true })).toBeVisible()
  expect(state.versions[state.job.revision].bib).toBe(BIB)
  await page.getByRole('button', { name: 'Saved versions', exact: true }).click(); await selectVersion(page, 0)
  await history.getByRole('button', { name: 'Restore version 0', exact: true }).click(); await expect(history).toBeHidden()
  await expect(panel(page)).toContainText('No bibliography attached.'); await expect(editor(page)).toHaveText(SOURCE, { useInnerText: true }); expect(state.job.revision).toBe(5)
})

test('dirty and pending imports protect editor text while failed requests and unknown keys stay actionable', async ({ page }) => {
  const state = await setup(page); await editor(page).fill(SOURCE + '\n% local draft')
  await expect(panel(page).getByRole('button', { name: 'Import bibliography', exact: true })).toBeDisabled(); expect(state.imports).toBe(0)
  await editor(page).fill(SOURCE)
  let release!: () => void; const started = new Promise<void>(resolve => { state.uploadStarted = resolve }); state.uploadGate = new Promise<void>(resolve => { release = resolve })
  await upload(page); await started; await expect(editor(page)).toHaveAttribute('aria-disabled', 'true')
  expect(state.versions[state.job.revision].source).toBe(SOURCE); release()
  await expect(panel(page)).toContainText('original.bib'); await expect(editor(page)).toHaveAttribute('aria-disabled', 'false')
  state.failImport = true; await upload(page, true); await expect(panel(page).getByRole('alert')).toContainText('Duplicate citation key')
  expect(state.job.revision).toBe(1); expect(state.versions[1].bib).toBe(BIB)
  state.failImport = false; state.conflict = true; await upload(page, true); await expect(panel(page).getByRole('alert')).toContainText('source or bibliography changed')
  state.failLoad = true; await panel(page).getByRole('button', { name: 'Reload bibliography', exact: true }).click(); await expect(panel(page).getByRole('alert')).toContainText('Library temporarily unavailable')
  state.failLoad = false; await panel(page).getByRole('button', { name: 'Reload bibliography', exact: true }).click(); await expect(panel(page)).toContainText('original.bib')
  await editor(page).fill(SOURCE.replace('Body text.', 'Body text. \\cite{unknown_key}')); await page.getByRole('button', { name: 'Save changes', exact: true }).click()
  await expect(panel(page)).toContainText('Unknown citation keys: unknown_key'); expect(state.versions[state.job.revision].bib).toBe(BIB)
})

test('setup loading can retry and stale setup never overwrites source or assets', async ({ page }) => {
  const state = await setup(page); await upload(page); await expect(panel(page)).toContainText('original.bib')
  state.failSetup = true; await panel(page).getByRole('button', { name: 'Preview bibliography setup', exact: true }).click()
  await expect(setupDialog(page).getByRole('alert')).toContainText('Setup temporarily unavailable')
  state.failSetup = false; await setupDialog(page).getByRole('button', { name: 'Preview again', exact: true }).click()
  await expect(setupDialog(page).getByLabel('After bibliography change 2')).toContainText(SECTION)
  state.setupConflict = true; await setupDialog(page).getByRole('button', { name: 'Apply bibliography setup', exact: true }).click()
  await expect(setupDialog(page).getByRole('alert')).toContainText('The source changed')
  await setupDialog(page).getByRole('button', { name: 'Cancel', exact: true }).click(); await expect(editor(page)).toHaveText(SOURCE, { useInnerText: true })
  expect(state.job.revision).toBe(1); expect(state.versions[1].bib).toBe(BIB)
})

test('a delayed older library response cannot hide the latest saved revision or unknown-key feedback', async ({ page }) => {
  await page.addInitScript(() => {
    const fetch = window.fetch.bind(window)
    window.fetch = async (input, options) => {
      if (typeof input !== 'string' || !/\/api\/jobs\/[^/]+\/bibliography$/.test(input) || options?.method === 'POST') return fetch(input, options)
      const response = await fetch(input, { ...options, signal: undefined }), json = response.json.bind(response)
      response.json = async () => { const data = await json() as BibliographyReport
        if (data.revision === 2) setTimeout(() => requestAnimationFrame(() => requestAnimationFrame(() => { document.documentElement.dataset.lateBibliographyConsumed = '1' })), 0)
        return data }
      return response
    }
  })
  const state = await setup(page); await upload(page); await expect(panel(page)).toContainText('original.bib')
  let release!: () => void; const started = new Promise<void>(resolve => { state.getStarted = resolve }); state.getGate = new Promise<void>(resolve => { release = resolve })
  await editor(page).fill(SOURCE + '\n% revision two'); await page.getByRole('button', { name: 'Save changes', exact: true }).click(); await started
  await expect(page.getByRole('button', { name: 'Save changes', exact: true })).toBeDisabled()
  await editor(page).fill(SOURCE.replace('Body text.', 'Body text. \\cite{unknown_key}')); await page.getByRole('button', { name: 'Save changes', exact: true }).click()
  await expect(panel(page)).toContainText('Saved source revision 3.'); await expect(panel(page)).toContainText('Unknown citation keys: unknown_key')
  release(); await expect(page.locator('html')).toHaveAttribute('data-late-bibliography-consumed', '1')
  await expect(panel(page)).toContainText('Saved source revision 3.'); await expect(panel(page)).toContainText('Unknown citation keys: unknown_key')
})
