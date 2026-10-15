import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import type { Job, SavedVersion } from '../src/api'

const ID = 'c0417d5c-9cab-4942-8703-595ff4b2fafe'
const ORIGINAL = '\\documentclass{article}\n\\begin{document}\nOriginal text\n\\end{document}'
const FIRST = ORIGINAL.replace('Original text', 'First saved ภาษาไทย')
const SECOND = ORIGINAL.replace('Original text', 'Second saved experiment')
const DIRTY = ORIGINAL.replace('Original text', 'Unsaved local text')
const editor = (page: Page) => page.getByRole('textbox', { name: 'LaTeX source editor' })
const history = (page: Page) => page.getByRole('dialog', { name: 'Saved versions', exact: true })
const preview = (page: Page) => history(page).getByRole('textbox', { name: 'Saved version source', exact: true })

async function setup(page: Page, legacy = false, historyCorrupt = false) {
  const first = legacy ? 5 : 0
  const job: Job = { id: ID, filename: 'versions.docx', template: 'article', status: 'COMPLETED',
    previewStatus: 'NOT_REQUESTED', message: 'Ready', previewMessage: '', createdAt: '2026-10-15T12:00:00Z',
    updatedAt: '2026-10-15T12:00:00Z', size: 100, revision: first }
  const versions = new Map<number, { source: string; origin: SavedVersion['origin']; restoredFrom: number }>([
    [first, { source: ORIGINAL, origin: legacy ? 'legacy' : 'converted', restoredFrom: -1 }],
  ])
  const state = { job, versions, historyFails: false, previewFails: false, limitFull: false, restores: 0, compiledSource: '', exportedSource: '', historyCorrupt, originalFails: false }
  await page.context().route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    const json = (body: unknown, status = 200) => route.fulfill({ status, json: body })
    const text = (body: string) => route.fulfill({ contentType: 'text/plain;charset=utf-8', body })
    const savedSource = () => state.versions.get(state.job.revision)!.source
    const save = (source: string, origin: SavedVersion['origin'], restoredFrom = -1) => {
      state.job = { ...state.job, revision: state.job.revision + 1, previewStatus: 'NOT_REQUESTED' }
      state.versions.set(state.job.revision, { source, origin, restoredFrom })
    }
    if (path === '/api/system') return json({ pandoc: true, tectonic: true, templates: ['article', 'report'], maxUploadBytes: 20971520 })
    if (path === '/api/jobs') return json([state.job])
    if (path === '/api/library') return json({ items: [state.job], page: 0, size: 20, total: 1 })
    if (path === `/api/jobs/${ID}`) return json(state.job)
    if (path.endsWith('/review')) return json({ elements: {}, findings: [], compilerWarnings: [], scope: 'Review manually.' })
    if (path === `/api/jobs/${ID}/source`) {
      if (state.historyCorrupt) return json({ message: 'Saved history recovery failed. Download the original Word document and back up project storage before repairing history.' }, 409)
      if (request.method() === 'PUT') {
        const body = request.postDataJSON() as { source: string; revision: number }
        if (body.revision !== state.job.revision) return json({ message: 'The source changed in another window.' }, 409)
        if (state.limitFull) return json({ message: 'Saved history is full. Existing versions have been preserved.' }, 507)
        save(body.source, 'saved'); return json(state.job)
      }
      return text(savedSource())
    }
    if (path === `/api/jobs/${ID}/versions`) {
      if (state.historyFails) return json({ message: 'Saved versions temporarily unavailable.' }, 503)
      const items: SavedVersion[] = [...state.versions].sort(([a], [b]) => b - a).map(([revision, version]) => ({
        revision, createdAt: '2026-10-15T12:00:00Z', bytes: Buffer.byteLength(version.source), origin: version.origin, restoredFrom: version.restoredFrom,
      }))
      return json({ items, currentRevision: state.job.revision, firstRevision: first, legacyHistoryUnavailable: legacy,
        maxVersions: 100, maxBytes: 104857600, bytesUsed: items.reduce((sum, version) => sum + version.bytes, 0) })
    }
    const versionPath = path.match(/\/versions\/(\d+)\/(source|restore)$/)
    if (versionPath) {
      const revision = Number(versionPath[1]), selected = state.versions.get(revision)
      if (!selected) return json({ message: 'Saved version missing.' }, 404)
      if (versionPath[2] === 'source') {
        if (state.previewFails) return json({ message: 'Version source temporarily unavailable.' }, 503)
        return text(selected.source)
      }
      state.restores++
      if ((request.postDataJSON() as { revision: number }).revision !== state.job.revision)
        return json({ message: 'The source changed in another window. Reopen before restoring a version.' }, 409)
      if (state.limitFull) return json({ message: 'Saved history is full. Existing versions have been preserved.' }, 507)
      save(selected.source, 'restored', revision)
      return json({ job: state.job, source: selected.source })
    }
    if (path.endsWith('/preview') && request.method() === 'POST') {
      state.compiledSource = savedSource(); state.job = { ...state.job, previewStatus: 'QUEUED' }; return json(state.job, 202)
    }
    if (path.endsWith('/download')) {
      state.exportedSource = savedSource()
      return route.fulfill({ contentType: 'application/zip', headers: { 'Content-Disposition': 'attachment; filename=project.zip' }, body: 'mock archive response' })
    }
    if (path.endsWith('/original')) {
      if (state.originalFails) return json({ message: 'Original download temporarily unavailable.' }, 503)
      return route.fulfill({ contentType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        headers: { 'Content-Disposition': 'attachment; filename=versions.docx' }, body: 'mock original document bytes' })
    }
    return json({ message: 'Unexpected request' }, 404)
  })
  await page.goto('/')
  await page.getByRole('button', { name: 'All documents', exact: true }).click()
  await page.getByRole('dialog', { name: 'All documents' }).getByRole('button', { name: 'Open versions.docx', exact: true }).click()
  if (!historyCorrupt) await expect(editor(page)).toHaveText(ORIGINAL, { useInnerText: true })
  return state
}

async function save(page: Page, source: string) {
  await editor(page).fill(source)
  await page.getByRole('button', { name: 'Save changes', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Save changes', exact: true })).toBeDisabled()
}

async function selectVersion(page: Page, revision: number) {
  const button = history(page).getByRole('navigation', { name: 'Saved source versions' }).getByRole('button', { name: new RegExp(`^Version ${revision}(?: |$)`) })
  await button.focus(); await button.press('Enter')
}

test('saved versions preview/download and restore survive reload before build and export requests', async ({ page }, testInfo) => {
  const state = await setup(page)
  await save(page, FIRST); await save(page, SECOND)
  await page.getByRole('button', { name: 'Saved versions', exact: true }).click()
  await expect(preview(page)).toHaveValue(SECOND)
  await selectVersion(page, 1)
  await expect(preview(page)).toHaveValue(FIRST)
  await expect(preview(page)).toHaveAttribute('readonly', '')
  await expect(page.getByText('Your source is saved', { exact: true })).toBeHidden()
  await history(page).screenshot({ path: testInfo.outputPath('saved-version-preview.png') })
  const downloading = page.waitForEvent('download')
  await history(page).getByRole('button', { name: 'Download version', exact: true }).click()
  expect(await readFile((await (await downloading).path())!, 'utf8')).toBe(FIRST)
  await history(page).getByRole('button', { name: 'Restore version 1', exact: true }).click()
  await expect(history(page)).toBeHidden(); await expect(editor(page)).toHaveText(FIRST, { useInnerText: true })
  expect(state.job.revision).toBe(3); expect(state.job.previewStatus).toBe('NOT_REQUESTED')
  expect(state.versions.get(0)?.source).toBe(ORIGINAL); expect(state.versions.get(2)?.source).toBe(SECOND)
  await page.reload(); await expect(editor(page)).toHaveText(FIRST, { useInnerText: true })
  await page.getByRole('tab', { name: 'Preview', exact: true }).click()
  await page.getByRole('button', { name: 'Build PDF preview', exact: true }).click()
  await expect(page.getByText('Setting your words in place…', { exact: true })).toBeVisible()
  expect(state.compiledSource).toBe(FIRST)
  await expect(page.getByRole('button', { name: 'Saved versions', exact: true })).toBeDisabled()
  const exporting = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Download ZIP', exact: true }).click()
  await exporting
  expect(state.exportedSource).toBe(FIRST) // Actual PDF/ZIP contents are verified in backend integration tests.
})

test('dirty-source restoration cancels safely and only clears drafts after a successful restore', async ({ page }) => {
  const state = await setup(page)
  await save(page, FIRST); await editor(page).fill(DIRTY)
  const requestRestore = async () => {
    await page.getByRole('button', { name: 'Saved versions', exact: true }).click()
    await selectVersion(page, 0)
    await expect(preview(page)).toHaveValue(ORIGINAL)
    await history(page).getByRole('button', { name: 'Restore version 0', exact: true }).click()
  }
  await requestRestore()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Keep editing', exact: true }).click()
  await history(page).getByRole('button', { name: 'Close', exact: true }).click()
  await expect(editor(page)).toHaveText(DIRTY, { useInnerText: true }); expect(state.restores).toBe(0)
  await requestRestore()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Discard changes', exact: true }).click()
  await expect(history(page)).toBeHidden(); await expect(editor(page)).toHaveText(ORIGINAL, { useInnerText: true })
  expect(state.job.revision).toBe(2)
  expect(await page.evaluate(id => Object.keys(localStorage).filter(key => key.startsWith(`doc2tex:draft:${id}:`)), ID)).toHaveLength(0)
})

test('a stale second-tab restore preserves unsaved editor text and its recovery draft', async ({ page }) => {
  const state = await setup(page)
  await editor(page).fill(DIRTY)
  await page.getByRole('button', { name: 'Saved versions', exact: true }).click()
  await expect(preview(page)).toHaveValue(ORIGINAL)
  state.versions.set(1, { source: 'Other tab saved', origin: 'saved', restoredFrom: -1 }); state.job = { ...state.job, revision: 1 }
  await history(page).getByRole('button', { name: 'Restore version 0', exact: true }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Discard changes', exact: true }).click()
  await expect(history(page).getByRole('alert')).toContainText('source changed in another window')
  await history(page).getByRole('button', { name: 'Close', exact: true }).click()
  await expect(editor(page)).toHaveText(DIRTY, { useInnerText: true })
  expect(state.job.revision).toBe(1); expect(state.versions.get(1)?.source).toBe('Other tab saved')
  expect(await page.evaluate(id => Object.keys(localStorage).filter(key => key.startsWith(`doc2tex:draft:${id}:`)), ID)).toHaveLength(1)
})

test('legacy history, request retries and storage limits show explicit usable states', async ({ page }) => {
  const state = await setup(page, true)
  state.historyFails = true; state.previewFails = true
  await page.getByRole('button', { name: 'Saved versions', exact: true }).click()
  await expect(history(page).getByRole('alert')).toContainText('Saved versions temporarily unavailable')
  state.historyFails = false
  await history(page).getByRole('button', { name: 'Retry versions', exact: true }).click()
  await expect(history(page)).toContainText('History begins at version 5')
  await expect(history(page)).toContainText('Earlier overwritten versions are unavailable')
  await expect(history(page).getByRole('alert')).toContainText('Version source temporarily unavailable')
  state.previewFails = false
  await history(page).getByRole('button', { name: 'Retry version source', exact: true }).click()
  await expect(preview(page)).toHaveValue(ORIGINAL)
  state.limitFull = true
  await history(page).getByRole('button', { name: 'Restore version 5', exact: true }).click()
  await expect(history(page).getByRole('alert')).toContainText('Saved history is full. Existing versions have been preserved.')
  expect(state.job.revision).toBe(5); expect(state.versions.size).toBe(1)
})

test('a history recovery failure keeps the original and local draft downloadable', async ({ page }, testInfo) => {
  await page.addInitScript(({ id, source }) => localStorage.setItem(`doc2tex:draft:${id}:0`, JSON.stringify({
    jobId: id, revision: 0, source, token: crypto.randomUUID(), updatedAt: Date.now(),
  })), { id: ID, source: DIRTY })
  const state = await setup(page, false, true)
  await expect(page.getByRole('alert')).toContainText('Saved history recovery failed')
  await expect(page.getByRole('button', { name: 'Save changes', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: 'Download ZIP', exact: true })).toBeDisabled()
  state.originalFails = true
  await page.getByRole('button', { name: 'Download original Word document', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Original download temporarily unavailable')
  state.originalFails = false
  const originalDownload = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Download original Word document', exact: true }).click()
  expect(await readFile((await (await originalDownload).path())!, 'utf8')).toBe('mock original document bytes')
  const recovery = page.getByRole('region', { name: 'Draft recovery' })
  await expect(recovery).toContainText('Saved source is unavailable')
  const draftDownload = page.waitForEvent('download')
  await recovery.getByRole('button', { name: 'Download draft', exact: true }).click()
  expect(await readFile((await (await draftDownload).path())!, 'utf8')).toBe(DIRTY)
  await page.getByRole('alert').screenshot({ path: testInfo.outputPath('history-recovery-error.png') })
  await page.getByRole('button', { name: 'All documents', exact: true }).click()
  await expect(page.getByRole('dialog', { name: 'All documents' }).getByRole('button', { name: 'Delete versions.docx', exact: true })).toBeEnabled()
})
