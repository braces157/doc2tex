import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import type { Job } from '../src/api'

const source = (id: string) => `\\documentclass{article}\n\\begin{document}\nDocument ${id}\n\\end{document}`
const editor = (page: Page) => page.getByRole('textbox', { name: 'LaTeX source editor' })

async function library(page: Page) {
  const jobs: Job[] = Array.from({ length: 65 }, (_, index) => ({
    id: `00000000-0000-0000-0000-${String(index + 1).padStart(12, '0')}`,
    filename: index === 0 ? 'older-résumé.docx' : `draft-${index}.docx`,
    template: index % 2 === 0 ? 'article' : 'report', status: index % 2 === 0 ? 'COMPLETED' : 'FAILED',
    previewStatus: 'NOT_REQUESTED', message: 'Saved.', previewMessage: '',
    createdAt: new Date(Date.UTC(2026, 0, 1, 0, index)).toISOString(), updatedAt: '2026-01-01T00:00:00Z', size: 1024, revision: 0,
  }))
  const state = { fails: false, requests: [] as URL[] }
  await page.context().route('**/api/**', async route => {
    const url = new URL(route.request().url())
    const path = url.pathname
    const json = (body: unknown, status = 200) => route.fulfill({ status, json: body })
    if (path === '/api/system') return json({ pandoc: true, tectonic: true, templates: ['article', 'report'], maxUploadBytes: 20971520 })
    if (path === '/api/jobs') return json([...jobs].reverse().slice(0, 30))
    if (path === '/api/library') {
      state.requests.push(url)
      if (state.fails) return json({ message: 'Library temporarily unavailable.' }, 503)
      const params = url.searchParams
      const filtered = jobs.filter(job => job.filename.toLowerCase().includes((params.get('query') ?? '').toLowerCase()) && (!params.get('status') || job.status === params.get('status')))
      if (params.get('sort') !== 'oldest') filtered.reverse()
      const pageIndex = Number(params.get('page')), size = Number(params.get('size'))
      return json({ items: filtered.slice(pageIndex * size, (pageIndex + 1) * size), page: pageIndex, size, total: filtered.length })
    }
    const job = jobs.find(job => path.startsWith(`/api/jobs/${job.id}`))
    if (job) {
      if (path.endsWith('/source')) return route.fulfill({ contentType: 'text/plain', body: source(job.id) })
      if (path.endsWith('/review')) return json({ elements: {}, findings: [], compilerWarnings: [], scope: 'Review manually.' })
      return json(job)
    }
    return json({ message: 'Missing document.' }, 404)
  })
  await page.goto('/')
  return { jobs, state }
}

test('all 65 documents are paginated and an older filename opens and restores outside recents', async ({ page }, testInfo) => {
  const { jobs, state } = await library(page)
  await page.getByRole('button', { name: 'All documents', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'All documents' })
  await expect(dialog.getByRole('status')).toHaveText('65 documents found')
  await expect(dialog.getByRole('listitem')).toHaveCount(20)
  await expect(dialog.getByRole('button', { name: 'Previous page' })).toBeDisabled()
  for (let index = 0; index < 3; index++) {
    await dialog.getByRole('button', { name: 'Next page' }).click()
    await expect(dialog.getByText(`Page ${index + 2} of 4`)).toBeVisible()
  }
  await expect(dialog.getByRole('listitem')).toHaveCount(5)
  await expect(dialog.getByRole('button', { name: 'Next page' })).toBeDisabled()
  await dialog.getByLabel('Filename').fill('older-résumé')
  await dialog.getByLabel('Filename').press('Enter')
  await expect(dialog.getByRole('status')).toHaveText('1 document found')
  expect(state.requests.at(-1)?.searchParams.get('page')).toBe('0')
  await page.screenshot({ path: testInfo.outputPath('library-search.png'), fullPage: true })
  // Open a document by keyboard using its named button.
  await dialog.getByRole('button', { name: /older-résumé.docx/ }).focus()
  await page.keyboard.press('Enter')
  await expect(editor(page)).toHaveValue(source(jobs[0].id))
  await page.reload()
  await expect(editor(page)).toHaveValue(source(jobs[0].id))
  await expect(page.getByRole('navigation', { name: 'Recent conversions' }).getByText('older-résumé.docx')).toHaveCount(0)
})

test('search combines with filters and sorting, and supports empty and retry states', async ({ page }) => {
  const { state } = await library(page)
  state.fails = true
  await page.getByRole('button', { name: 'All documents', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'All documents' })
  await expect(dialog.getByRole('alert')).toContainText('Library temporarily unavailable')
  state.fails = false
  await dialog.getByRole('button', { name: 'Retry library' }).click()
  await expect(dialog.getByRole('status')).toHaveText('65 documents found')
  await dialog.getByLabel('Status', { exact: true }).selectOption('FAILED')
  await expect(dialog.getByRole('status')).toHaveText('32 documents found')
  await dialog.getByLabel('Sort', { exact: true }).selectOption('oldest')
  await expect(dialog.getByRole('listitem').first()).toContainText('draft-1.docx')
  await dialog.getByLabel('Filename').fill('older-résumé')
  await dialog.getByRole('button', { name: 'Search', exact: true }).click()
  await expect(dialog.getByRole('status')).toHaveText('0 documents found')
  await expect(dialog.getByText('No matching documents.', { exact: false })).toBeVisible()
  await dialog.getByLabel('Status', { exact: true }).selectOption('COMPLETED')
  await expect(dialog.getByRole('status')).toHaveText('1 document found')
})

test('library switching preserves dirty-source confirmation and local recovery', async ({ page }) => {
  const { jobs } = await library(page)
  await page.getByRole('button', { name: 'All documents', exact: true }).click()
  const libraryDialog = () => page.getByRole('dialog', { name: 'All documents' })
  await libraryDialog().getByRole('button', { name: /draft-64.docx/ }).click()
  await editor(page).fill('Unsaved Unicode edits ภาษาไทย')
  const selectOlder = async () => {
    await page.getByRole('button', { name: 'All documents', exact: true }).click()
    await libraryDialog().getByLabel('Filename').fill('older-résumé')
    await libraryDialog().getByRole('button', { name: 'Search', exact: true }).click()
    await libraryDialog().getByRole('button', { name: /older-résumé.docx/ }).click()
  }
  await selectOlder()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Keep editing' }).click()
  await expect(editor(page)).toHaveValue('Unsaved Unicode edits ภาษาไทย')
  await page.reload()
  await page.getByRole('button', { name: 'Restore draft', exact: true }).click()
  await expect(editor(page)).toHaveValue('Unsaved Unicode edits ภาษาไทย')
  await selectOlder()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Discard changes' }).click()
  await expect(editor(page)).toHaveValue(source(jobs[0].id))
})

test('loading results cannot replace a newer filter response', async ({ page }) => {
  const { jobs } = await library(page)
  let release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  let requested!: () => void
  const pending = new Promise<void>(resolve => { requested = resolve })
  await page.route('**/api/library?**', async route => {
    if (new URL(route.request().url()).searchParams.get('status')) return route.fallback()
    requested()
    await gate
    return route.fulfill({ json: { items: [jobs[0]], page: 0, size: 20, total: 65 } })
  })
  await page.getByRole('button', { name: 'All documents', exact: true }).click()
  await pending
  const dialog = page.getByRole('dialog', { name: 'All documents' })
  await expect(dialog.getByRole('status')).toHaveText('Loading documents…')
  await expect(dialog.getByRole('button', { name: 'Next page' })).toBeDisabled()
  await dialog.getByLabel('Status', { exact: true }).selectOption('FAILED')
  await expect(dialog.getByRole('status')).toHaveText('32 documents found')
  release()
  await expect(dialog.getByRole('listitem').first()).toContainText('draft-63.docx')
  await expect(dialog.getByRole('button', { name: /older-résumé/ })).toHaveCount(0)
})

test('a delayed active-document restore respects navigation back to a new conversion', async ({ page }) => {
  const { jobs } = await library(page)
  await page.evaluate(id => sessionStorage.setItem('doc2tex:active-job', id), jobs[0].id)
  let release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  let requested!: () => void
  const pending = new Promise<void>(resolve => { requested = resolve })
  await page.route(`**/api/jobs/${jobs[0].id}`, async route => {
    requested()
    await gate
    return route.fulfill({ json: jobs[0] })
  })
  await page.reload()
  await pending
  await page.getByRole('button', { name: 'New conversion', exact: true }).click()
  release()
  await expect(editor(page)).toHaveCount(0)
  await expect(page.getByRole('heading', { name: 'A fresh page starts here.' })).toBeVisible()
  expect(await page.evaluate(() => sessionStorage.getItem('doc2tex:active-job'))).toBeNull()
})
