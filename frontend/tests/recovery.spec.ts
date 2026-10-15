import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import type { Job } from '../src/api'

const ORIGINAL = '\\documentclass{article}\n\\begin{document}\nOriginal text.\n\\end{document}'
const EDITED = ORIGINAL.replace('Original text.', 'Recovered edits.')
const ID = '6be38a9e-f02d-4ff8-978c-bb0d2e9775c5'

async function workspace(page: Page) {
  const job: Job = { id: ID, filename: 'research.docx', template: 'article', status: 'COMPLETED',
    previewStatus: 'NOT_REQUESTED', message: 'Ready.', previewMessage: '',
    createdAt: '2026-10-15T00:00:00Z', updatedAt: '2026-10-15T00:00:00Z', size: 1024, revision: 0 }
  const state = { job, source: ORIGINAL, sourceFails: false, uploadFails: false, saves: 0, changeDuringLoad: false }
  await page.context().route('**/api/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const json = (body: unknown, status = 200) => route.fulfill({ status, json: body })
    if (path === '/api/system') return json({ pandoc: true, tectonic: true, maxUploadBytes: 20 * 1024 * 1024, templates: ['article', 'report'] })
    if (path === '/api/jobs' && request.method() === 'GET') return json([state.job])
    if (path === '/api/jobs' && request.method() === 'POST') {
      if (state.uploadFails) return json({ message: 'Upload failed. Please retry.' }, 503)
      state.job = { ...state.job, id: 'dbd702e9-31ee-451a-a92b-aa193dcbf004', filename: 'replacement.docx', revision: 0 }
      state.source = ORIGINAL
      return json(state.job, 202)
    }
    if (path === `/api/jobs/${state.job.id}`) return json(state.job)
    if (path === `/api/jobs/${state.job.id}/review`) return json({ elements: {}, findings: [], compilerWarnings: [], scope: 'Source counts.' })
    if (path === `/api/jobs/${state.job.id}/source`) {
      if (request.method() === 'PUT') {
        state.saves++
        const body = request.postDataJSON() as { source: string; revision: number }
        if (body.revision !== state.job.revision) return json({ message: 'The source changed in another window. Reopen this conversion before saving.' }, 409)
        state.source = body.source
        state.job = { ...state.job, revision: state.job.revision + 1 }
        return json(state.job)
      }
      if (state.sourceFails) return json({ message: 'The source is temporarily unavailable.' }, 503)
      if (state.changeDuringLoad) {
        state.changeDuringLoad = false
        state.job = { ...state.job, revision: state.job.revision + 1 }
        state.source = ORIGINAL.replace('Original', 'New saved')
      }
      return route.fulfill({ contentType: 'text/plain', body: state.source })
    }
    return json({ message: 'Unexpected request.' }, 404)
  })
  page.on('dialog', dialog => dialog.accept())
  await page.goto('/')
  return state
}

async function openDocument(page: Page, mobile: boolean) {
  if (mobile) {
    await page.getByRole('button', { name: 'Recent documents', exact: true }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'research.docx' }).click()
  } else await page.getByRole('navigation', { name: 'Recent conversions' }).getByRole('button', { name: /research.docx/ }).click()
}

const editor = (page: Page) => page.getByRole('textbox', { name: 'LaTeX source editor' })
const recovery = (page: Page) => page.getByRole('region', { name: 'Draft recovery' })
const drafts = (page: Page) => page.evaluate(() => Object.keys(localStorage).filter(key => key.startsWith('doc2tex:draft:')))

test('restores unsaved edits after reloading the active document', async ({ page, isMobile }, testInfo) => {
  await workspace(page)
  await openDocument(page, isMobile)
  await expect(editor(page)).toHaveValue(ORIGINAL)
  await editor(page).fill(EDITED)
  await page.reload()
  await expect(recovery(page)).toContainText('An unsaved draft is available')
  await expect(editor(page)).toBeDisabled()
  await page.screenshot({ path: testInfo.outputPath('draft-recovery.png'), fullPage: true })
  await recovery(page).getByRole('button', { name: 'Restore draft', exact: true }).click()
  await expect(editor(page)).toHaveValue(EDITED)
  await expect(page.getByRole('button', { name: 'Save changes', exact: true })).toBeEnabled()
  await expect(recovery(page)).toBeHidden()
})

test('discards a recovered draft and keeps the saved source', async ({ page, isMobile }) => {
  await workspace(page)
  await openDocument(page, isMobile)
  await editor(page).fill(EDITED)
  await page.reload()
  await recovery(page).getByRole('button', { name: 'Discard draft' }).click()
  await expect(editor(page)).toHaveValue(ORIGINAL)
  await expect(editor(page)).toBeEnabled()
  expect(await drafts(page)).toHaveLength(0)
  await page.reload()
  await expect(editor(page)).toHaveValue(ORIGINAL)
  await expect(recovery(page)).toBeHidden()
})

test('successful saving clears the draft and advances the saved revision', async ({ page, isMobile }) => {
  const state = await workspace(page)
  await openDocument(page, isMobile)
  await editor(page).fill(EDITED)
  await page.getByRole('button', { name: 'Save changes', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Save changes', exact: true })).toBeDisabled()
  expect(state.job.revision).toBe(1)
  expect(await drafts(page)).toHaveLength(0)
  await page.reload()
  await expect(editor(page)).toHaveValue(EDITED)
  await expect(recovery(page)).toBeHidden()
})

test('failed source loading blocks editing and exporting, then retries without losing the draft', async ({ page, isMobile }) => {
  const state = await workspace(page)
  state.sourceFails = true
  await openDocument(page, isMobile)
  await expect(page.getByText('Could not load your source', { exact: true })).toBeVisible()
  await expect(editor(page)).toBeHidden()
  await expect(page.getByRole('button', { name: 'Download ZIP', exact: true })).toBeDisabled()
  await page.getByRole('tab', { name: 'Preview', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Build PDF preview', exact: true })).toBeHidden()
  state.sourceFails = false
  await page.getByRole('button', { name: 'Retry loading source' }).click()
  await page.getByRole('tab', { name: 'Source', exact: true }).click()
  await editor(page).fill(EDITED)
  state.sourceFails = true
  await page.reload()
  await expect(page.getByRole('button', { name: 'Retry loading source' })).toBeVisible()
  expect(await drafts(page)).toHaveLength(1)
  state.sourceFails = false
  await page.getByRole('button', { name: 'Retry loading source' }).click()
  await recovery(page).getByRole('button', { name: 'Restore draft', exact: true }).click()
  await expect(editor(page)).toHaveValue(EDITED)
})

test('conflicting saves preserve drafts and offer older revisions as a download', async ({ page, isMobile }) => {
  const state = await workspace(page)
  await openDocument(page, isMobile)
  await editor(page).fill(EDITED)
  state.job = { ...state.job, revision: 1 }
  state.source = ORIGINAL.replace('Original', 'Other window')
  await page.getByRole('button', { name: 'Save changes', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('The source changed in another window')
  expect(await drafts(page)).toHaveLength(1)
  await page.reload()
  await expect(recovery(page)).toContainText('Your saved source has changed')
  await expect(recovery(page).getByRole('button', { name: 'Restore draft', exact: true })).toBeHidden()
  await expect(editor(page)).toBeDisabled()
  const downloading = page.waitForEvent('download')
  await recovery(page).getByRole('button', { name: 'Download draft' }).click()
  const download = await downloading
  expect(await readFile((await download.path())!, 'utf8')).toBe(EDITED)
  expect(state.saves).toBe(1)
  await recovery(page).getByRole('button', { name: 'Discard draft' }).click()
  await expect(editor(page)).toHaveValue(state.source)
})

test('a failed replacement upload preserves the current edits and their recovery copy', async ({ page, isMobile }) => {
  const state = await workspace(page)
  await openDocument(page, isMobile)
  await editor(page).fill(EDITED)
  await page.getByLabel('Upload Word document').setInputFiles({ name: 'replacement.docx', mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', buffer: Buffer.from('synthetic upload') })
  state.uploadFails = true
  await page.getByRole('button', { name: 'Convert to LaTeX', exact: true }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Discard changes' }).click()
  await expect(page.getByRole('alert')).toContainText('Upload failed')
  await expect(editor(page)).toHaveValue(EDITED)
  expect(await drafts(page)).toHaveLength(1)
})

test('storage failures leave editing and server saving available', async ({ page, isMobile }) => {
  await page.addInitScript(() => {
    Storage.prototype.setItem = () => { throw new DOMException('Storage full', 'QuotaExceededError') }
  })
  const state = await workspace(page)
  await openDocument(page, isMobile)
  await editor(page).fill(EDITED)
  await expect(page.getByRole('alert')).toContainText('Local draft recovery is unavailable')
  await page.getByRole('button', { name: 'Save changes', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Save changes', exact: true })).toBeDisabled()
  expect(state.source).toBe(EDITED)
})

test('detects source changes during loading and retries with consistent metadata', async ({ page, isMobile }) => {
  const state = await workspace(page)
  state.changeDuringLoad = true
  await openDocument(page, isMobile)
  await expect(page.getByRole('alert')).toContainText('The source changed while opening it')
  await page.getByRole('button', { name: 'Retry loading source' }).click()
  await expect(editor(page)).toHaveValue(state.source)
  await editor(page).fill(EDITED)
  await page.getByRole('button', { name: 'Save changes', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Save changes', exact: true })).toBeDisabled()
  expect(state.job.revision).toBe(2)
})

test('explicitly discarding edits for a successful conversion removes the recovery copy', async ({ page, isMobile }) => {
  await workspace(page)
  await openDocument(page, isMobile)
  await editor(page).fill(EDITED)
  await page.getByLabel('Upload Word document').setInputFiles({ name: 'replacement.docx', mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', buffer: Buffer.from('synthetic upload') })
  await page.getByRole('button', { name: 'Convert to LaTeX', exact: true }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Discard changes' }).click()
  await expect(editor(page)).toHaveValue(ORIGINAL)
  expect(await drafts(page)).toHaveLength(0)
  await expect(recovery(page)).toBeHidden()
})

test('saving in one window does not delete a draft updated by another window', async ({ page, isMobile }) => {
  await workspace(page)
  await openDocument(page, isMobile)
  await editor(page).fill(EDITED)
  const other = await page.context().newPage()
  other.on('dialog', dialog => dialog.accept())
  await other.goto('/')
  await openDocument(other, isMobile)
  await recovery(other).getByRole('button', { name: 'Restore draft', exact: true }).click()
  const otherEdits = EDITED.replace('Recovered edits.', 'Second window edits.')
  await editor(other).fill(otherEdits)
  await page.getByRole('button', { name: 'Save changes', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Save changes', exact: true })).toBeDisabled()
  expect(await drafts(page)).toHaveLength(1)
  await other.reload()
  await expect(recovery(other)).toContainText('Your saved source has changed')
  const downloading = other.waitForEvent('download')
  await recovery(other).getByRole('button', { name: 'Download draft' }).click()
  expect(await readFile((await (await downloading).path())!, 'utf8')).toBe(otherEdits)
})

for (const blank of [{ name: 'empty', source: '' }, { name: 'whitespace-only', source: ' \n\t ' }]) {
test(`a ${blank.name} draft stays editable and recoverable without allowing save or build`, async ({ page, isMobile }) => {
  const state = await workspace(page)
  await openDocument(page, isMobile)
  await editor(page).fill(blank.source)
  await expect(editor(page)).toBeEnabled()
  await expect(page.getByRole('button', { name: 'Save changes', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: 'Download ZIP', exact: true })).toBeDisabled()
  await page.getByRole('tab', { name: 'Preview', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Build PDF preview', exact: true })).toBeDisabled()
  expect(state.saves).toBe(0)
  await page.reload()
  await recovery(page).getByRole('button', { name: 'Restore draft', exact: true }).click()
  await expect(editor(page)).toHaveValue(blank.source)
  await expect(editor(page)).toBeEnabled()
  await editor(page).fill(EDITED)
  await expect(page.getByRole('button', { name: 'Save changes', exact: true })).toBeEnabled()
})
}
