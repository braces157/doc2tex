import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import type { Job, LayoutOptions } from '../src/api'
import { DEFAULT_LAYOUT } from '../src/api'

const ID = '53601f4c-c39b-4cea-af84-684c99f027a8'
const COPY = 'f6fa9fd6-2acf-4c0f-b4df-3a27269a3803'
const SOURCE = '\\documentclass{article}\n\\begin{document}\nOriginal body\n\\end{document}'
const editor = (page: Page) => page.getByRole('textbox', { name: 'LaTeX source editor', exact: true })
const copyDialog = (page: Page) => page.getByRole('dialog', { name: 'Create a layout copy', exact: true })

async function setup(page: Page, existing = true) {
  const parent: Job = { id: ID, filename: 'layout.docx', template: 'article', status: 'COMPLETED', previewStatus: 'NOT_REQUESTED', message: 'Ready', previewMessage: '',
    createdAt: '2026-10-16T00:00:00Z', updatedAt: '2026-10-16T00:00:00Z', size: 100, revision: 0,
    layout: { paper: 'letter', fontSize: 10, marginMm: 25.4, title: '', author: '', date: '' } }
  const state = { jobs: existing ? [parent] : [] as Job[], sources: new Map([[ID, SOURCE]]), uploads: 0, copies: 0, conflict: false,
    uploadedLayout: null as LayoutOptions | null, copiedLayout: null as LayoutOptions | null }
  await page.context().route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    const json = (body: unknown, status = 200) => route.fulfill({ status, json: body })
    if (path === '/api/system') return json({ pandoc: true, tectonic: true, templates: ['article', 'report'], maxUploadBytes: 20971520 })
    if (path === '/api/jobs' && request.method() === 'POST') {
      state.uploads++
      const body = request.postData()!, layout = JSON.parse(body.match(/name="layout"\r\n\r\n([\s\S]*?)\r\n--/)![1]) as LayoutOptions
      const template = body.match(/name="template"\r\n\r\n([\s\S]*?)\r\n--/)![1]
      state.uploadedLayout = layout
      const job = { ...parent, template, layout }; state.jobs = [job]; return json(job, 202)
    }
    if (path === '/api/jobs') return json(state.jobs)
    if (path === '/api/library') return json({ items: state.jobs, page: 0, size: 20, total: state.jobs.length })
    const id = path.match(/^\/api\/jobs\/([^/]+)/)?.[1], job = state.jobs.find(job => job.id === id)
    if (!job) return json({ message: 'Missing job' }, 404)
    if (path === `/api/jobs/${id}`) return json(job)
    if (path.endsWith('/review')) return json({ elements: {}, findings: [], compilerWarnings: [], scope: 'Review manually.', revision: job.revision })
    if (path.endsWith('/diagnostics')) return json({ revision: null, status: 'NOT_REQUESTED', items: [], logTruncated: false, diagnosticsTruncated: false })
    if (path.endsWith('/source')) {
      if (request.method() === 'PUT') {
        const body = request.postDataJSON() as { source: string; revision: number }; expect(body.revision).toBe(job.revision)
        state.sources.set(job.id, body.source); job.revision++; return json(job)
      }
      return route.fulfill({ contentType: 'text/plain', body: state.sources.get(job.id)! })
    }
    if (path.endsWith('/layout-copy')) {
      state.copies++
      const body = request.postDataJSON() as { revision: number; template: string; layout: LayoutOptions }
      expect(body.revision).toBe(job.revision)
      if (state.conflict) return json({ message: 'The source changed. Reopen this document before creating a layout copy.' }, 409)
      state.copiedLayout = body.layout
      const copy: Job = { ...parent, id: COPY, displayName: 'layout.docx · layout copy', template: body.template, layout: body.layout, revision: 0 }
      state.jobs.unshift(copy); state.sources.set(COPY, SOURCE.replace('Original body', 'Separate original conversion')); return json(copy, 202)
    }
    return json({ message: 'Unexpected request' }, 404)
  })
  await page.goto('/')
  if (existing) {
    await page.getByRole('button', { name: 'All documents', exact: true }).click()
    await page.getByRole('dialog', { name: 'All documents' }).getByRole('button', { name: 'Open layout.docx', exact: true }).click()
    await expect(editor(page)).toHaveText(SOURCE, { useInnerText: true })
  }
  await page.getByText('Layout options', { exact: true }).click()
  await expect(page.getByRole('combobox', { name: 'Layout preset', exact: true })).toBeVisible()
  return state
}

test('new conversions retain original layout by default and reload the saved choice', async ({ page }, testInfo) => {
  const state = await setup(page, false)
  const original = page.getByRole('checkbox', { name: 'Use original page size and margins', exact: true })
  const ai = page.getByRole('checkbox', { name: 'AI layout assistance', exact: true })
  await expect(ai).toBeChecked()
  await expect(original).toBeChecked()
  await expect(page.getByRole('combobox', { name: 'Layout preset', exact: true })).toHaveValue('original')
  await expect(page.getByRole('combobox', { name: 'Paper size', exact: true })).toBeDisabled()
  await expect(page.getByLabel('Margins (mm)', { exact: true })).toBeDisabled()
  await page.getByRole('combobox', { name: 'Font size', exact: true }).selectOption('12')
  await page.getByLabel('Document title', { exact: true }).fill('Original layout title')
  await page.getByLabel('Author', { exact: true }).fill('Original author')
  await page.getByLabel('Date text', { exact: true }).fill('2026')
  await expect(original).toBeChecked()
  await page.locator('.ui-layout-options').screenshot({ path: testInfo.outputPath('original-layout-options.png') })
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1)
  await page.getByLabel('Upload Word document', { exact: true }).setInputFiles({ name: 'original.docx', mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', buffer: Buffer.from('synthetic original upload') })
  await page.getByRole('button', { name: 'Convert to LaTeX', exact: true }).click()
  await expect(editor(page)).toHaveText(SOURCE, { useInnerText: true })
  expect(state.uploadedLayout).toEqual({ ...DEFAULT_LAYOUT, fontSize: 12, title: 'Original layout title', author: 'Original author', date: '2026' })
  await expect(page.getByText('Saved layout: Original page size and margins · 12 pt.', { exact: true })).toBeVisible()
  await expect(page.getByText('Saved AI layout assistance: On.', { exact: true })).toBeVisible()
  await page.reload(); await page.getByText('Layout options', { exact: true }).click()
  await expect(original).toBeChecked()
  await expect(ai).toBeChecked()
  await expect(page.getByRole('combobox', { name: 'Paper size', exact: true })).toBeDisabled()
  await expect(page.getByLabel('Margins (mm)', { exact: true })).toBeDisabled()
  await expect(page.getByRole('combobox', { name: 'Font size', exact: true })).toHaveValue('12')
  await expect(page.getByLabel('Document title', { exact: true })).toHaveValue('Original layout title')
  await expect(page.getByLabel('Author', { exact: true })).toHaveValue('Original author')
  await expect(page.getByLabel('Date text', { exact: true })).toHaveValue('2026')
})

test('clearing the margin then enabling original layout restores a usable fallback', async ({ page }) => {
  await setup(page)
  const original = page.getByRole('checkbox', { name: 'Use original page size and margins', exact: true })
  const margins = page.getByLabel('Margins (mm)', { exact: true })
  await original.uncheck()
  await margins.fill('')
  await expect(page.getByRole('alert')).toBeVisible()
  await original.check()
  await expect(margins).toBeDisabled()
  await expect(margins).toHaveValue('25.4')
  await expect(page.getByRole('alert')).toHaveCount(0)
})

test('legacy saved layouts remain manual and original-layout copies preserve parent edits', async ({ page }) => {
  const state = await setup(page)
  const original = page.getByRole('checkbox', { name: 'Use original page size and margins', exact: true })
  const ai = page.getByRole('checkbox', { name: 'AI layout assistance', exact: true })
  await expect(ai).not.toBeChecked()
  await expect(page.getByText('Saved AI layout assistance: Off.', { exact: true })).toBeVisible()
  await expect(original).not.toBeChecked()
  await expect(page.getByRole('combobox', { name: 'Paper size', exact: true })).toBeEnabled()
  await expect(page.getByText('Saved layout: LETTER · 10 pt · 25.4 mm margins.', { exact: true })).toBeVisible()
  await original.check()
  await expect(ai).not.toBeChecked()
  await expect(page.getByLabel('Margins (mm)', { exact: true })).toBeDisabled()
  await original.uncheck()
  await expect(page.getByRole('combobox', { name: 'Layout preset', exact: true })).toHaveValue('custom')
  await page.getByRole('combobox', { name: 'Paper size', exact: true }).selectOption('a4')
  await page.getByLabel('Margins (mm)', { exact: true }).fill('21.5')
  await expect(original).not.toBeChecked()
  await original.check()
  await ai.check()
  const edited = SOURCE + '\n% AUTO_COPY_PARENT_EDITS'
  await editor(page).fill(edited)
  await page.getByRole('button', { name: 'Save changes', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Create layout copy', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: 'Create layout copy', exact: true }).click()
  await expect(copyDialog(page)).toContainText('Article · Original page size and margins · 10 pt.')
  await expect(copyDialog(page)).toContainText('AI layout assistance: On.')
  await expect(copyDialog(page)).not.toContainText('21.5 mm margins')
  await copyDialog(page).getByRole('button', { name: 'Create separate conversion', exact: true }).click()
  await expect(copyDialog(page)).toBeHidden()
  await expect(editor(page)).toContainText('Separate original conversion')
  expect(state.sources.get(ID)).toBe(edited)
  expect(state.copiedLayout).toEqual({ ...DEFAULT_LAYOUT, paper: 'a4', marginMm: 21.5 })
  await expect(page.getByText('Saved layout: Original page size and margins · 10 pt.', { exact: true })).toBeVisible()
  await page.reload(); await page.getByText('Layout options', { exact: true }).click()
  await expect(original).toBeChecked()
  await expect(ai).toBeChecked()
  await page.getByRole('button', { name: 'All documents', exact: true }).click()
  await page.getByRole('dialog', { name: 'All documents' }).getByRole('button', { name: 'Open layout.docx', exact: true }).click()
  await expect(editor(page)).toHaveText(edited, { useInnerText: true })
  await expect(original).not.toBeChecked()
  await expect(ai).not.toBeChecked()
})

test('AI opt-out survives manual presets, upload, and reload independently of original page layout', async ({ page }) => {
  const state = await setup(page, false)
  const original = page.getByRole('checkbox', { name: 'Use original page size and margins', exact: true })
  const ai = page.getByRole('checkbox', { name: 'AI layout assistance', exact: true })
  await ai.uncheck()
  await expect(original).toBeChecked()
  await page.getByRole('combobox', { name: 'Layout preset', exact: true }).selectOption('paper')
  await expect(original).not.toBeChecked()
  await expect(ai).not.toBeChecked()
  await original.check()
  await expect(ai).not.toBeChecked()
  await original.uncheck()
  await page.getByLabel('Upload Word document', { exact: true }).setInputFiles({ name: 'manual.docx', mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', buffer: Buffer.from('synthetic manual upload') })
  await page.getByRole('button', { name: 'Convert to LaTeX', exact: true }).click()
  await expect(editor(page)).toHaveText(SOURCE, { useInnerText: true })
  expect(state.uploadedLayout).toEqual({ ...DEFAULT_LAYOUT, paper: 'a4', fontSize: 11, marginMm: 25, preserveSourceLayout: false, aiLayout: false })
  await page.reload(); await page.getByText('Layout options', { exact: true }).click()
  await expect(ai).not.toBeChecked()
  await expect(original).not.toBeChecked()
  await expect(page.getByText('Saved AI layout assistance: Off.', { exact: true })).toBeVisible()
})

test('a new upload carries a generic preset and Unicode metadata, and reload restores saved settings', async ({ page }, testInfo) => {
  const state = await setup(page, false)
  await expect(page.getByRole('checkbox', { name: 'Use original page size and margins', exact: true })).toBeChecked()
  await page.getByRole('combobox', { name: 'Layout preset', exact: true }).selectOption('paper')
  await expect(page.getByRole('checkbox', { name: 'AI layout assistance', exact: true })).toBeChecked()
  await expect(page.getByRole('checkbox', { name: 'Use original page size and margins', exact: true })).not.toBeChecked()
  await expect(page.getByRole('combobox', { name: 'Paper size', exact: true })).toHaveValue('a4')
  await expect(page.getByRole('combobox', { name: 'Font size', exact: true })).toHaveValue('11')
  await page.getByLabel('Document title', { exact: true }).fill('Metadata ภาษาไทย中文 & #1')
  await page.getByLabel('Author', { exact: true }).fill('Jane Doe')
  await page.getByLabel('Date text', { exact: true }).fill('October 2026')
  await page.getByLabel('Margins (mm)', { exact: true }).fill('22.5')
  await page.getByLabel('Upload Word document', { exact: true }).setInputFiles({ name: 'layout.docx', mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', buffer: Buffer.from('synthetic mock upload') })
  await page.getByRole('button', { name: 'Convert to LaTeX', exact: true }).click()
  await expect(editor(page)).toHaveText(SOURCE, { useInnerText: true })
  expect(state.uploadedLayout).toEqual({ paper: 'a4', fontSize: 11, marginMm: 22.5, title: 'Metadata ภาษาไทย中文 & #1', author: 'Jane Doe', date: 'October 2026', preserveSourceLayout: false, aiLayout: true })
  await page.reload(); await page.getByText('Layout options', { exact: true }).click()
  await expect(page.getByLabel('Document title', { exact: true })).toHaveValue('Metadata ภาษาไทย中文 & #1')
  await expect(page.getByRole('checkbox', { name: 'Use original page size and margins', exact: true })).not.toBeChecked()
  await expect(page.getByRole('combobox', { name: 'Paper size', exact: true })).toHaveValue('a4'); await expect(page.getByLabel('Margins (mm)', { exact: true })).toHaveValue('22.5')
  await page.locator('.ui-layout-options').screenshot({ path: testInfo.outputPath('layout-options.png') })
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1)
  await page.getByRole('button', { name: 'New conversion', exact: true }).click()
  await expect(page.getByLabel('Document title', { exact: true })).toHaveValue('')
  await expect(page.getByLabel('Author', { exact: true })).toHaveValue(''); await expect(page.getByLabel('Date text', { exact: true })).toHaveValue('')
  await expect(page.getByRole('combobox', { name: 'Paper size', exact: true })).toHaveValue('letter')
  await expect(page.getByRole('combobox', { name: 'Font size', exact: true })).toHaveValue('10')
  await expect(page.getByLabel('Margins (mm)', { exact: true })).toHaveValue('25.4')
  await expect(page.getByRole('checkbox', { name: 'Use original page size and margins', exact: true })).toBeChecked()
  await page.getByLabel('Upload Word document', { exact: true }).setInputFiles({ name: 'fresh.docx', mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', buffer: Buffer.from('synthetic fresh upload') })
  await page.getByRole('button', { name: 'Convert to LaTeX', exact: true }).click(); await expect(editor(page)).toHaveText(SOURCE, { useInnerText: true })
  expect(state.uploads).toBe(2); expect(state.uploadedLayout).toEqual(DEFAULT_LAYOUT)
})

test('presets preserve dirty edits, cancellation writes nothing, and a layout copy keeps the saved parent', async ({ page }) => {
  const state = await setup(page), edited = SOURCE + '\n% KEEP_SAVED_EDITS'
  await editor(page).fill(edited)
  await page.getByRole('combobox', { name: 'Layout preset', exact: true }).selectOption('report')
  await expect(editor(page)).toHaveText(edited, { useInnerText: true })
  await expect(page.getByRole('button', { name: 'Create layout copy', exact: true })).toBeDisabled()
  await expect(page.getByText('Save your source edits before creating a layout copy.', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Save changes', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Create layout copy', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: 'Create layout copy', exact: true }).click()
  await expect(copyDialog(page)).toContainText('source edits are not carried into the copy')
  await copyDialog(page).getByRole('button', { name: 'Cancel', exact: true }).click()
  expect(state.copies).toBe(0); await expect(editor(page)).toHaveText(edited, { useInnerText: true })
  await page.getByRole('button', { name: 'Create layout copy', exact: true }).click()
  await copyDialog(page).getByRole('button', { name: 'Create separate conversion', exact: true }).click()
  await expect(copyDialog(page)).toBeHidden(); await expect(editor(page)).toContainText('Separate original conversion')
  expect(state.sources.get(ID)).toBe(edited); expect(state.copiedLayout).toEqual({ ...DEFAULT_LAYOUT, fontSize: 12, preserveSourceLayout: false, aiLayout: false })
  await page.getByRole('button', { name: 'All documents', exact: true }).click()
  await page.getByRole('dialog', { name: 'All documents' }).getByRole('button', { name: 'Open layout.docx', exact: true }).click()
  await expect(editor(page)).toHaveText(edited, { useInnerText: true })
  await expect(page.getByRole('combobox', { name: 'Font size', exact: true })).toHaveValue('10')
})

test('invalid settings block conversion/copy and stale copy errors retain the current source', async ({ page }) => {
  const state = await setup(page)
  await page.getByLabel('Margins (mm)', { exact: true }).fill('9.9')
  await expect(page.getByRole('button', { name: 'Create layout copy', exact: true })).toBeDisabled()
  await expect(page.getByRole('alert')).toContainText('Choose margins from 10 to 50 mm')
  await page.getByLabel('Margins (mm)', { exact: true }).fill('20')
  state.conflict = true; await page.getByRole('button', { name: 'Create layout copy', exact: true }).click()
  await copyDialog(page).getByRole('button', { name: 'Create separate conversion', exact: true }).click()
  await expect(copyDialog(page).getByRole('alert')).toContainText('The source changed')
  await copyDialog(page).getByRole('button', { name: 'Cancel', exact: true }).click()
  await expect(editor(page)).toHaveText(SOURCE, { useInnerText: true }); expect(state.jobs).toHaveLength(1)
  expect(state.sources.get(ID)).toBe(SOURCE)
})
