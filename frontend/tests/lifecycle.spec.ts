import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import type { Job } from '../src/api'

const ID = '4d9767bb-26e9-44e6-a5a6-d809636d8210'
const ORIGINAL = '\\documentclass{article}\n\\begin{document}\nOriginal text\n\\end{document}'
const EDITED = ORIGINAL.replace('Original text', 'Unsaved ภาษาไทย')
const editor = (page: Page) => page.getByRole('textbox', { name: 'LaTeX source editor' })
const libraryDialog = (page: Page) => page.getByRole('dialog', { name: 'All documents', exact: true })

async function setup(page: Page) {
  const job: Job = { id: ID, filename: 'research.docx', displayName: '', deletionPending: false,
    template: 'article', status: 'COMPLETED', previewStatus: 'NOT_REQUESTED', message: 'Ready', previewMessage: '',
    createdAt: '2026-10-15T12:00:00Z', updatedAt: '2026-10-15T12:00:00Z', size: 100, revision: 0 }
  const state = { job, source: ORIGINAL, deleted: false, deleteFails: false, busy: false, deletes: 0, saves: 0 }
  await page.context().route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname
    const json = (body: unknown, status = 200) => route.fulfill({ status, json: body })
    if (path === '/api/system') return json({ pandoc: true, tectonic: true, templates: ['article', 'report'], maxUploadBytes: 20971520 })
    if (path === '/api/jobs') return json(state.deleted ? [] : [state.job])
    if (path === '/api/library') return json({ items: state.deleted ? [] : [state.job], page: 0, size: 20, total: state.deleted ? 0 : 1 })
    if (!path.startsWith(`/api/jobs/${ID}`)) return json({ message: 'Unexpected request' }, 404)
    if (state.deleted) return json({ message: 'This conversion could not be found.' }, 404)
    if (request.method() === 'DELETE') {
      state.deletes++
      if (state.busy) return json({ message: 'This document is busy. Wait for its download to finish, then retry Delete.' }, 409)
      if (state.deleteFails) {
        state.job = { ...state.job, deletionPending: true }
        return json({ message: 'Deletion could not finish. Retry Delete in All documents.' }, 503)
      }
      state.deleted = true
      return route.fulfill({ status: 204 })
    }
    if (state.job.deletionPending) return json({ message: 'Cleanup is incomplete; retry Delete in All documents.' }, 410)
    if (request.method() === 'PATCH') {
      state.job = { ...state.job, displayName: (request.postDataJSON() as { displayName: string }).displayName.trim() }
      return json(state.job)
    }
    if (path.endsWith('/source')) {
      if (request.method() === 'PUT') {
        state.saves++
        state.source = (request.postDataJSON() as { source: string }).source
        state.job = { ...state.job, revision: state.job.revision + 1 }
        return json(state.job)
      }
      return route.fulfill({ contentType: 'text/plain', body: state.source })
    }
    if (path.endsWith('/review')) return json({ elements: {}, findings: [], compilerWarnings: [], scope: 'Review manually.' })
    return json(state.job)
  })
  await page.goto('/')
  return state
}

async function open(page: Page, title = 'research.docx') {
  await page.getByRole('button', { name: 'All documents', exact: true }).click()
  await libraryDialog(page).getByRole('button', { name: `Open ${title}`, exact: true }).click()
  await expect(editor(page)).toHaveText(ORIGINAL, { useInnerText: true })
}

async function requestDelete(page: Page, title = 'research.docx') {
  await page.getByRole('button', { name: 'All documents', exact: true }).click()
  await libraryDialog(page).getByRole('button', { name: `Delete ${title}`, exact: true }).click()
  await expect(page.getByRole('alertdialog')).toContainText('original Word document, source, saved versions, media, and PDF previews')
}

test('renames persist after reload without changing the filename, source or draft', async ({ page }, testInfo) => {
  const state = await setup(page)
  await open(page)
  await editor(page).fill(EDITED)
  await page.getByRole('button', { name: 'All documents', exact: true }).click()
  await libraryDialog(page).getByRole('button', { name: 'Rename research.docx', exact: true }).click()
  const rename = page.getByRole('dialog', { name: 'Rename document', exact: true })
  await rename.getByLabel('Display name').fill('Paper ภาษาไทย')
  await rename.getByRole('button', { name: 'Save name' }).click()
  await expect(rename).toBeHidden()
  await expect(libraryDialog(page).getByRole('button', { name: 'Open Paper ภาษาไทย', exact: true })).toBeVisible()
  await expect(libraryDialog(page)).toContainText('Original: research.docx')
  await page.screenshot({ path: testInfo.outputPath('renamed-library.png'), fullPage: true })
  await libraryDialog(page).getByRole('button', { name: 'Close', exact: true }).click()
  await expect(editor(page)).toHaveText(EDITED, { useInnerText: true })
  expect(state.job.filename).toBe('research.docx'); expect(state.job.revision).toBe(0); expect(state.saves).toBe(0)
  await page.reload()
  await page.getByRole('button', { name: 'Restore draft', exact: true }).click()
  await expect(editor(page)).toHaveText(EDITED, { useInnerText: true })
  await expect(page.locator('.ui-topbar')).toContainText('Paper ภาษาไทย')
})

test('cancel keeps source and draft; confirmed deletion clears only the matching browser keys', async ({ page }) => {
  const state = await setup(page)
  await open(page)
  await editor(page).fill(EDITED)
  await page.evaluate(() => localStorage.setItem('doc2tex:draft:unrelated:0', 'keep'))
  await requestDelete(page)
  await page.getByRole('alertdialog').getByRole('button', { name: 'Cancel', exact: true }).click()
  expect(state.deletes).toBe(0)
  expect(await page.evaluate(id => sessionStorage.getItem('doc2tex:active-job') === id, ID)).toBe(true)
  expect(await page.evaluate(id => Object.keys(localStorage).some(key => key.startsWith(`doc2tex:draft:${id}:`)), ID)).toBe(true)
  await libraryDialog(page).getByRole('button', { name: 'Delete research.docx', exact: true }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Delete document', exact: true }).click()
  await expect(page.getByRole('alertdialog')).toBeHidden()
  await expect(libraryDialog(page).getByRole('status')).toHaveText('0 documents found')
  expect(state.deleted).toBe(true)
  expect(await page.evaluate(() => sessionStorage.getItem('doc2tex:active-job'))).toBeNull()
  expect(await page.evaluate(id => Object.keys(localStorage).some(key => key.startsWith(`doc2tex:draft:${id}:`)), ID)).toBe(false)
  expect(await page.evaluate(() => localStorage.getItem('doc2tex:draft:unrelated:0'))).toBe('keep')
  await libraryDialog(page).getByRole('button', { name: 'Close', exact: true }).click()
  await expect(editor(page)).toHaveCount(0)
  await page.reload()
  await expect(editor(page)).toHaveCount(0)
})

test('busy and cleanup failures keep drafts and allow a successful retry', async ({ page }) => {
  const state = await setup(page)
  await open(page)
  await editor(page).fill(EDITED)
  state.busy = true
  await requestDelete(page)
  const confirmation = page.getByRole('alertdialog')
  await confirmation.getByRole('button', { name: 'Delete document', exact: true }).click()
  await expect(confirmation.getByRole('alert')).toContainText('document is busy')
  state.busy = false; state.deleteFails = true
  await confirmation.getByRole('button', { name: 'Delete document', exact: true }).click()
  await expect(confirmation.getByRole('alert')).toContainText('Deletion could not finish')
  expect(await page.evaluate(() => sessionStorage.getItem('doc2tex:active-job'))).toBe(ID)
  expect(await page.evaluate(id => Object.keys(localStorage).some(key => key.startsWith(`doc2tex:draft:${id}:`)), ID)).toBe(true)
  await confirmation.getByRole('button', { name: 'Cancel', exact: true }).click()
  await expect(libraryDialog(page).getByRole('button', { name: 'Open research.docx', exact: true })).toBeDisabled()
  await libraryDialog(page).getByRole('button', { name: 'Retry delete research.docx', exact: true }).click()
  state.deleteFails = false
  await confirmation.getByRole('button', { name: 'Delete document', exact: true }).click()
  await expect(confirmation).toBeHidden()
  expect(state.deleted).toBe(true)
})

test('another tab retains downloadable in-memory edits after server deletion', async ({ page }) => {
  const state = await setup(page)
  await open(page)
  const other = await page.context().newPage()
  await other.goto('/')
  await open(other)
  await editor(other).fill(EDITED)
  await requestDelete(page)
  await page.getByRole('alertdialog').getByRole('button', { name: 'Delete document', exact: true }).click()
  await expect(other.getByRole('alert', { name: 'Document unavailable' })).toBeVisible()
  await expect(editor(other)).toHaveText(EDITED, { useInnerText: true })
  await expect(editor(other)).toBeDisabled()
  await expect(other.getByRole('button', { name: 'Save changes', exact: true })).toBeDisabled()
  const downloading = other.waitForEvent('download')
  await other.getByRole('button', { name: 'Download unsaved text' }).click()
  expect(await readFile((await (await downloading).path())!, 'utf8')).toBe(EDITED)
  expect(state.saves).toBe(0)
  expect(await other.evaluate(() => sessionStorage.getItem('doc2tex:active-job'))).toBeNull()
})

for (const incomplete of [false, true]) test(`a ${incomplete ? 'cleanup-pending' : 'missing-document'} save response preserves in-memory text and its local draft`, async ({ page }) => {
  const state = await setup(page)
  await open(page)
  await editor(page).fill(EDITED)
  if (incomplete) state.job = { ...state.job, deletionPending: true }
  else state.deleted = true
  await page.getByRole('button', { name: 'Save changes', exact: true }).click()
  await expect(page.getByRole('alert', { name: 'Document unavailable' })).toBeVisible()
  await expect(editor(page)).toHaveText(EDITED, { useInnerText: true })
  expect(await page.evaluate(id => Object.keys(localStorage).some(key => key.startsWith(`doc2tex:draft:${id}:`)), ID)).toBe(true)
  if (incomplete) expect(await page.evaluate(() => sessionStorage.getItem('doc2tex:active-job'))).toBe(ID)
  const downloading = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Download unsaved text' }).click()
  expect(await readFile((await (await downloading).path())!, 'utf8')).toBe(EDITED)
})

test('opening a stale library result retains a downloadable local recovery draft', async ({ page }) => {
  const state = await setup(page)
  await page.getByRole('button', { name: 'All documents', exact: true }).click()
  await expect(libraryDialog(page).getByRole('button', { name: 'Open research.docx', exact: true })).toBeVisible()
  await page.evaluate(({ id, source }) => localStorage.setItem(`doc2tex:draft:${id}:0`, JSON.stringify({
    jobId: id, revision: 0, source, updatedAt: Date.now(), token: crypto.randomUUID(),
  })), { id: ID, source: EDITED })
  state.deleted = true
  await libraryDialog(page).getByRole('button', { name: 'Open research.docx', exact: true }).click()
  const recovery = page.getByRole('region', { name: 'Draft recovery' })
  await expect(recovery).toContainText('Saved source is unavailable')
  await expect(recovery.getByRole('button', { name: 'Restore draft', exact: true })).toHaveCount(0)
  const downloading = page.waitForEvent('download')
  await recovery.getByRole('button', { name: 'Download draft', exact: true }).click()
  expect(await readFile((await (await downloading).path())!, 'utf8')).toBe(EDITED)
})
