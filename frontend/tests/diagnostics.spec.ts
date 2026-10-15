import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import type { CompilerDiagnostic, CompilerReport, Job } from '../src/api'

const ID = '460f136b-5e6b-4f2c-8e03-89a1646f5546'
const SOURCE = '\\documentclass{article}\n\\begin{document}\n\\UnknownCommand\n\\end{document}'
const issue: CompilerDiagnostic = { severity: 'error', code: 'undefined-command', title: 'Unknown LaTeX command',
  explanation: 'Check the command spelling and whether its required package is loaded.', file: 'main.tex', line: 3,
  raw: 'error: main.tex:3: Undefined control sequence\nl.3 \\UnknownCommand' }

async function setup(page: Page, report: CompilerReport = { revision: 1, status: 'FAILED', logTruncated: false, diagnosticsTruncated: false, items: [issue] }) {
  const job: Job = { id: ID, filename: 'diagnostics.docx', template: 'article', status: 'COMPLETED', previewStatus: 'FAILED',
    message: 'Ready', previewMessage: 'PDF compilation failed.', createdAt: '2026-10-16T00:00:00Z', updatedAt: '2026-10-16T00:00:00Z', size: 50, revision: 1 }
  const state = { job, report, source: SOURCE, fail: false, gate: null as Promise<void> | null }
  await page.context().route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    const json = (body: unknown, status = 200) => route.fulfill({ status, json: body })
    if (path === '/api/system') return json({ pandoc: true, tectonic: true, templates: ['article', 'report'], maxUploadBytes: 20971520 })
    if (path === '/api/jobs') return json([state.job])
    if (path === '/api/library') return json({ items: [state.job], page: 0, size: 20, total: 1 })
    if (path === `/api/jobs/${ID}`) return json(state.job)
    if (path.endsWith('/review')) return json({ elements: {}, findings: [], compilerWarnings: [], scope: 'Review original.' })
    if (path.endsWith('/diagnostics')) {
      if (state.gate) await state.gate
      if (state.fail) return json({ message: 'Diagnostics temporarily unavailable.' }, 503)
      return json(state.report)
    }
    if (path.endsWith('/source')) {
      if (route.request().method() === 'PUT') {
        const body = route.request().postDataJSON() as { source: string; revision: number }
        if (body.revision !== state.job.revision) return json({ message: 'Revision conflict' }, 409)
        state.source = body.source; state.job = { ...state.job, revision: state.job.revision + 1, previewStatus: 'NOT_REQUESTED' }; return json(state.job)
      }
      return route.fulfill({ contentType: 'text/plain', body: state.source })
    }
    return json({ message: 'Unexpected request' }, 404)
  })
  await page.goto('/')
  await page.getByRole('button', { name: 'All documents', exact: true }).click()
  await page.getByRole('dialog', { name: 'All documents' }).getByRole('button', { name: 'Open diagnostics.docx', exact: true }).click()
  await expect(page.getByRole('textbox', { name: 'LaTeX source editor', exact: true })).toBeVisible()
  return state
}

test('failed build diagnostics navigate from Preview to the current source line', async ({ page }, testInfo) => {
  await setup(page)
  await page.getByRole('tab', { name: 'Preview', exact: true }).click()
  const panel = page.getByRole('region', { name: 'Compiler diagnostics' })
  await expect(panel.getByRole('heading', { name: 'PDF build failed', exact: true })).toBeVisible()
  await expect(panel).toContainText('Unknown LaTeX command')
  await panel.getByText('Compiler output', { exact: true }).click()
  await expect(panel).toContainText('l.3 \\UnknownCommand')
  await panel.screenshot({ path: testInfo.outputPath('compiler-diagnostics.png') })
  await panel.getByRole('button', { name: 'Go to source line 3', exact: true }).click()
  await expect(page.getByRole('tab', { name: 'Source', exact: true })).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByRole('textbox', { name: 'LaTeX source editor', exact: true })).toBeFocused()
  await expect(page.getByText('Line 3 of 4', { exact: true })).toBeVisible()
  await page.keyboard.press('Control+Home')
  await page.getByRole('tab', { name: 'Preview', exact: true }).click()
  await page.getByRole('tab', { name: 'Source', exact: true }).click()
  await expect(page.getByText('Line 1 of 4', { exact: true })).toBeVisible()
})

test('unsaved edits and a newer saved revision disable old diagnostic navigation', async ({ page }) => {
  const state = await setup(page)
  const go = page.getByRole('button', { name: 'Go to source line 3', exact: true })
  await expect(go).toBeEnabled()
  await page.getByRole('textbox', { name: 'LaTeX source editor', exact: true }).fill(SOURCE.replace('UnknownCommand', 'ChangedCommand'))
  await expect(go).toBeDisabled(); await expect(page.getByText(/These diagnostics are stale/)).toBeVisible()
  await page.getByRole('button', { name: 'Save changes', exact: true }).click()
  await expect(page.getByText('Build revision 1 · Current revision 2', { exact: true })).toBeVisible()
  await expect(go).toBeDisabled(); expect(state.report.revision).toBe(1)
  await page.reload()
  await expect(page.getByText('Build revision 1 · Current revision 2', { exact: true })).toBeVisible()
  await expect(go).toBeDisabled()
})

test('package and unverified legacy locations never navigate or link local paths', async ({ page }) => {
  await setup(page, { revision: null, status: 'FAILED', logTruncated: false, diagnosticsTruncated: false,
    items: [issue, { ...issue, file: 'C:\\private\\package\\main.tex', line: 27 }, { ...issue, file: 'package.sty', line: 2 }, { ...issue, file: null, line: null }] })
  const panel = page.getByRole('region', { name: 'Compiler diagnostics' })
  await expect(panel).toContainText('source revision for these diagnostics is unknown')
  await expect(panel.getByRole('button')).toHaveCount(1)
  await expect(panel.getByRole('button')).toBeDisabled()
  await expect(panel.getByRole('link')).toHaveCount(1)
  await expect(panel.getByRole('link')).toHaveAttribute('href', `/api/jobs/${ID}/preview/log`)
})

test('warning-only and truncated unknown output remain visible with raw-log access', async ({ page }) => {
  await setup(page, { revision: 1, status: 'READY', logTruncated: true, diagnosticsTruncated: true,
    items: [{ ...issue, severity: 'warning', code: 'overfull-box', title: 'Content exceeds its box', raw: 'warning: main.tex:3: Overfull \\hbox' },
      { ...issue, severity: 'warning', code: 'compiler-message', title: 'Compiler message', file: null, line: null, raw: 'Unknown warning\nUseful continuation' }] })
  const panel = page.getByRole('region', { name: 'Compiler diagnostics' })
  await expect(panel.getByRole('heading', { name: 'PDF built with warnings', exact: true })).toBeVisible()
  await expect(panel).toContainText('truncated after 64 KiB'); await expect(panel).toContainText('first 200 diagnostics')
  await panel.getByText('Compiler output', { exact: true }).nth(1).click()
  await expect(panel).toContainText('Useful continuation')
  await expect(panel.getByRole('link', { name: 'Open raw compiler log', exact: true })).toBeVisible()
})

test('diagnostic loading failures disable navigation until a successful retry', async ({ page }) => {
  const state = await setup(page)
  state.fail = true
  await page.getByRole('textbox', { name: 'LaTeX source editor', exact: true }).fill(SOURCE + '\n% edit')
  await page.getByRole('button', { name: 'Save changes', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Retry diagnostics', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Go to source line 3', exact: true })).toBeDisabled()
  state.fail = false; state.report = { ...state.report, revision: 2 }
  await page.getByRole('button', { name: 'Retry diagnostics', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Go to source line 3', exact: true })).toBeEnabled()
})
