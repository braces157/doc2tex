import { expect, test } from '@playwright/test'

test('setup distinguishes tools, fonts and PDF downloads and retries without losing source', async ({ page }, testInfo) => {
  let failed = false, ready = false
  const id = '977ae63b-b2de-41af-8c40-e713bcd79ad6'
  const job = { id, filename: 'Synthetic setup.docx', template: 'article', status: 'COMPLETED', previewStatus: 'NOT_REQUESTED', revision: 0, size: 100, message: 'Ready', previewMessage: '', createdAt: '2026-10-16T00:00:00Z', updatedAt: '2026-10-16T00:00:00Z' }
  const source = '\\documentclass{article}\n\\begin{document}\nSynthetic saved source.\n\\end{document}'
  await page.addInitScript(id => sessionStorage.setItem('doc2tex:active-job', id), id)
  await page.context().route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/system') return failed ? route.fulfill({ status: 503, json: { message: 'Synthetic setup check unavailable' } }) : route.fulfill({ json: {
      pandoc: ready, tectonic: false, maxUploadBytes: 20971520, templates: ['article', 'report'], packaged: true,
      aiLayout: { state: ready ? 'ready' : 'missing', guidance: ready ? 'Local AI layout is available.' : 'Run the AI layout setup script. Standard conversion remains available.' },
      diagnostics: [{ name: 'Pandoc', state: ready ? 'ready' : 'missing', version: ready ? 'pandoc synthetic' : '', guidance: ready ? 'Pandoc is ready.' : 'Close the app and run Install tools.cmd.' },
        { name: 'Tectonic', state: 'unusable', version: '', guidance: 'Repair the PDF compiler. Source and ZIP export remain available.' }],
      missingFonts: ['NotoSansThai-Regular.ttf'], texGuidance: 'The first PDF build may download TeX packages. A populated cache is not proof every document works offline.' } })
    if (path === '/api/jobs') return route.fulfill({ json: [job] })
    if (path === `/api/jobs/${id}`) return route.fulfill({ json: job })
    if (path === `/api/jobs/${id}/source`) return route.fulfill({ contentType: 'text/plain', body: source })
    return route.fulfill({ status: 404, json: { message: 'Unexpected request' } })
  })
  await page.goto('/')
  const editor = page.getByRole('textbox', { name: 'LaTeX source editor', exact: true })
  await expect(editor).toHaveText(source, { useInnerText: true })
  const draft = source.replace('Synthetic saved source.', 'Synthetic unsaved source to retain.')
  await editor.fill(draft)
  if (testInfo.project.name === 'desktop') {
    await page.getByRole('button', { name: 'Setup diagnostics', exact: true }).hover()
    await expect(page.getByRole('tooltip')).toContainText('Open Setup diagnostics to install or repair')
  }
  await page.getByRole('button', { name: 'Setup diagnostics', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'Setup diagnostics', exact: true })
  await expect(dialog).toContainText('Pandoc · missing'); await expect(dialog).toContainText('Tectonic · unusable')
  await expect(dialog).toContainText('AI layout assistance · missing'); await expect(dialog).toContainText('Standard conversion remains available')
  await expect(dialog).toContainText('NotoSansThai-Regular.ttf'); await expect(dialog).toContainText('first PDF build may download')
  await expect(dialog).toContainText('Projects and settings are retained'); await dialog.screenshot({ path: testInfo.outputPath('setup-diagnostics.png') })
  failed = true; await dialog.getByRole('button', { name: 'Check again', exact: true }).click()
  await expect(dialog.getByRole('alert')).toContainText('Synthetic setup check unavailable')
  failed = false; ready = true; await dialog.getByRole('button', { name: 'Check again', exact: true }).click()
  await expect(dialog).toContainText('Pandoc · ready'); await expect(dialog).toContainText('pandoc synthetic')
  await expect(dialog).toContainText('AI layout assistance · ready')
  await dialog.getByRole('button', { name: 'Close', exact: true }).last().click()
  await expect(page.getByRole('button', { name: 'Setup diagnostics', exact: true })).toContainText('Ready to convert')
  await expect(editor).toHaveText(draft, { useInnerText: true })
  await expect(page.getByRole('button', { name: 'Save changes', exact: true })).toBeEnabled()
})
