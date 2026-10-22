import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import type { Job } from '../src/api'

const ID = '210f136b-5e6b-4f2c-8e03-89a1646f5595'
const ORIGINAL = '\\documentclass{article}\n\\begin{document}\nOriginal ภาษาไทย 中文\n\\end{document}'
const editor = (page: Page) => page.getByRole('textbox', { name: 'LaTeX source editor', exact: true })

async function setup(page: Page, source = ORIGINAL, mac = false) {
  await page.addInitScript(platform => Object.defineProperty(navigator, 'platform', { get: () => platform }), mac ? 'MacIntel' : 'Win32')
  const job: Job = { id: ID, filename: 'editor.docx', template: 'article', status: 'COMPLETED', previewStatus: 'NOT_REQUESTED',
    message: 'Ready', previewMessage: '', createdAt: '2026-10-15T12:00:00Z', updatedAt: '2026-10-15T12:00:00Z', size: 100, revision: 0 }
  const state = { job, source, saves: 0, builds: 0, sourceFails: false, tectonic: true, saveGate: null as Promise<void> | null }
  await page.context().route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    const json = (body: unknown, status = 200) => route.fulfill({ status, json: body })
    if (path === '/api/system') return json({ pandoc: true, tectonic: state.tectonic, templates: ['article', 'report'], maxUploadBytes: 20971520 })
    if (path === '/api/jobs') return json([state.job])
    if (path === '/api/library') return json({ items: [state.job], page: 0, size: 20, total: 1 })
    if (path === `/api/jobs/${ID}`) return json(state.job)
    if (path.endsWith('/review')) return json({ elements: {}, findings: [], compilerWarnings: [], scope: 'Review manually.' })
    if (path.endsWith('/source')) {
      if (request.method() === 'PUT') {
        state.saves++
        if (state.saveGate) await state.saveGate
        const body = request.postDataJSON() as { source: string; revision: number }
        if (body.revision !== state.job.revision) return json({ message: 'Source changed in another window.' }, 409)
        state.source = body.source; state.job = { ...state.job, revision: state.job.revision + 1, previewStatus: 'NOT_REQUESTED' }
        return json(state.job)
      }
      if (state.sourceFails) return json({ message: 'Source temporarily unavailable.' }, 503)
      return route.fulfill({ contentType: 'text/plain;charset=utf-8', body: state.source })
    }
    if (path.endsWith('/preview') && request.method() === 'POST') {
      state.builds++; state.job = { ...state.job, previewStatus: 'QUEUED' }; return json(state.job, 202)
    }
    return json({ message: 'Unexpected request' }, 404)
  })
  await page.goto('/')
  await page.getByRole('button', { name: 'All documents', exact: true }).click()
  await page.getByRole('dialog', { name: 'All documents' }).getByRole('button', { name: 'Open editor.docx', exact: true }).click()
  await expect(editor(page)).toBeVisible()
  return state
}

test('find/replace and local undo/redo preserve Unicode draft recovery', async ({ page }, testInfo) => {
  const state = await setup(page)
  await expect(editor(page)).toHaveText(ORIGINAL, { useInnerText: true })
  await expect(editor(page)).toHaveAccessibleDescription(/Tab moves focus/)
  expect(await editor(page).locator('.cm-line span[class]').count()).toBeGreaterThan(0)
  await page.getByRole('button', { name: 'Find / replace', exact: true }).click()
  await page.getByRole('textbox', { name: 'Find', exact: true }).fill('Original')
  await page.getByRole('textbox', { name: 'Replace', exact: true }).fill('Replaced α')
  await page.getByRole('button', { name: 'replace all', exact: true }).click()
  await expect(editor(page)).toContainText('Replaced α ภาษาไทย 中文', { useInnerText: true })
  await page.getByRole('button', { name: 'Undo', exact: true }).click()
  await expect(editor(page)).toHaveText(ORIGINAL, { useInnerText: true })
  await page.getByRole('button', { name: 'Redo', exact: true }).click()
  await expect(editor(page)).toContainText('Replaced α ภาษาไทย 中文', { useInnerText: true })
  await page.getByRole('tab', { name: 'Preview', exact: true }).click()
  await page.getByRole('tab', { name: 'Review', exact: true }).click()
  await page.getByRole('tab', { name: 'Source', exact: true }).click()
  await page.getByRole('button', { name: 'Undo', exact: true }).click()
  await expect(editor(page)).toHaveText(ORIGINAL, { useInnerText: true })
  await page.getByRole('button', { name: 'Redo', exact: true }).click()
  await page.locator('.latex-editor-shell').screenshot({ path: testInfo.outputPath('latex-editing-tools.png') })
  expect(state.saves).toBe(0); expect(state.builds).toBe(0)
  await page.reload()
  await page.getByRole('button', { name: 'Restore draft', exact: true }).click()
  await expect(editor(page)).toContainText('Replaced α ภาษาไทย 中文', { useInnerText: true })
  await page.getByRole('button', { name: 'Save changes', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Save changes', exact: true })).toBeDisabled()
  expect(state.source).toBe(ORIGINAL.replace('Original', 'Replaced α'))
})

test('line navigation clamps positions, indentation works and Tab leaves the editor', async ({ page }) => {
  await setup(page, ORIGINAL.replace('Original ภาษาไทย 中文', `Original ${'long '.repeat(100)}`))
  const line = page.getByRole('spinbutton', { name: 'Go to line', exact: true })
  await line.fill('999999'); await page.getByRole('button', { name: 'Go', exact: true }).click()
  await expect(page.getByText('Line 4 of 4', { exact: true })).toBeVisible(); await expect(editor(page)).toBeFocused()
  await line.fill('-20'); await line.press('Enter')
  await expect(page.getByText('Line 1 of 4', { exact: true })).toBeVisible()
  await line.fill('2.9'); await line.press('Enter')
  await expect(page.getByText('Line 2 of 4', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Indent', exact: true }).click()
  const indented = await editor(page).locator('.cm-line').nth(1).innerText()
  expect(indented).toBe('  \\begin{document}')
  await page.getByRole('button', { name: 'Outdent', exact: true }).click()
  expect(await editor(page).locator('.cm-line').nth(1).innerText()).toBe('\\begin{document}')
  await page.getByRole('button', { name: 'Wrap', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Wrap', exact: true })).toHaveAttribute('aria-pressed', 'true')
  await expect.poll(() => page.locator('.cm-scroller').evaluate(element => element.scrollWidth - element.clientWidth)).toBeLessThanOrEqual(2)
  await editor(page).focus(); await page.keyboard.press('Tab')
  await expect(editor(page)).not.toBeFocused()
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1)
})

async function selectLine(page: Page, number: number) {
  const line = page.getByRole('spinbutton', { name: 'Go to line', exact: true })
  await line.fill(String(number)); await line.press('Enter')
  await page.keyboard.press('Home'); await page.keyboard.press('Shift+End')
}

test('formatting preserves selected Unicode, supports shortcuts, and undoes each insertion', async ({ page }) => {
  const state = await setup(page)
  await selectLine(page, 3)
  await page.getByRole('button', { name: 'Bold', exact: true }).click()
  await expect(editor(page)).toContainText('\\textbf{Original ภาษาไทย 中文}', { useInnerText: true })
  await expect(editor(page)).toBeFocused()
  await page.keyboard.press('Control+i')
  await expect(editor(page)).toContainText('\\textbf{\\textit{Original ภาษาไทย 中文}}', { useInnerText: true })
  await page.getByRole('button', { name: 'Undo', exact: true }).click()
  await expect(editor(page)).toContainText('\\textbf{Original ภาษาไทย 中文}', { useInnerText: true })
  await page.getByRole('button', { name: 'Undo', exact: true }).click()
  await expect(editor(page)).toHaveText(ORIGINAL, { useInnerText: true })
  await page.getByRole('button', { name: 'Toggle comment', exact: true }).click()
  await expect(editor(page)).toContainText('% Original ภาษาไทย 中文', { useInnerText: true })
  await page.keyboard.press('Control+/')
  await expect(editor(page)).toHaveText(ORIGINAL, { useInnerText: true })
  expect(state.saves).toBe(0); expect(state.builds).toBe(0)
})

test('Insert selects useful placeholders and keeps its edits in the recoverable draft', async ({ page }) => {
  const state = await setup(page)
  await selectLine(page, 3)
  await page.keyboard.press('Backspace')
  await page.getByRole('button', { name: 'Insert', exact: true }).click()
  await page.getByRole('menuitem', { name: 'Section heading', exact: true }).click()
  await expect(editor(page)).toBeFocused()
  await page.keyboard.insertText('Methods ภาษาไทย')
  await expect(editor(page)).toContainText('\\section{Methods ภาษาไทย}', { useInnerText: true })
  await page.keyboard.press('End'); await page.keyboard.press('Enter')
  await page.getByRole('button', { name: 'Insert', exact: true }).click()
  await page.getByRole('menuitem', { name: 'Bulleted list', exact: true }).click()
  await expect(editor(page)).toBeFocused()
  await page.keyboard.insertText('A useful change')
  await expect(editor(page)).toContainText('\\begin{itemize}\n  \\item A useful change\n\\end{itemize}', { useInnerText: true })
  await page.getByRole('button', { name: 'Save changes', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Save changes', exact: true })).toBeDisabled()
  expect(state.source).toContain('\\section{Methods ภาษาไทย}')
  expect(state.source).toContain('\\item A useful change')
  expect(state.builds).toBe(0)
})

test('command completion and paired braces support keyboard editing without trapping Tab', async ({ page }) => {
  await setup(page)
  await selectLine(page, 3); await page.keyboard.press('Backspace')
  await page.keyboard.type('\\sec')
  await expect(page.getByRole('option').filter({ hasText: '\\section' })).toBeVisible()
  await page.keyboard.press('Enter')
  await page.keyboard.insertText('Results 中文')
  await expect(editor(page)).toContainText('\\section{Results 中文}', { useInnerText: true })
  await page.keyboard.press('End'); await page.keyboard.press('Enter')
  await page.keyboard.type('{')
  await expect(editor(page)).toContainText('{}', { useInnerText: true })
  await page.keyboard.type('x'); await page.keyboard.type('}')
  await expect(editor(page)).toContainText('{x}', { useInnerText: true })
  await expect(editor(page)).not.toContainText('{x}}', { useInnerText: true })
  await page.keyboard.press('Tab'); await expect(editor(page)).not.toBeFocused()
  await selectLine(page, 4); await page.keyboard.press('Backspace')
  await page.keyboard.type('\\{ \\(')
  await expect(editor(page).locator('.cm-line').nth(3)).toHaveText('\\{ \\(')
  await selectLine(page, 4); await page.keyboard.press('Backspace')
  await page.keyboard.type('% \\sec')
  await expect(page.getByRole('option')).toHaveCount(0)
  await selectLine(page, 4); await page.keyboard.press('Backspace')
  await page.keyboard.type('\\sec')
  await expect(page.getByRole('option').first()).toBeVisible()
  await page.keyboard.press('Tab'); await expect(editor(page)).not.toBeFocused()
})

test('outline follows edits, ignores comments and verbatim, and offers room and guidance', async ({ page }, testInfo) => {
  const source = '\\documentclass{article}\n\\begin{document}\n\\section{Introduction}\nText to edit.\n% \\section{Comment}\n\\begin{verbatim}\n\\section{Example only}\n\\end{verbatim}\n\\subsection{Nested {title}}\nDetails.\n\\end{document}'
  await setup(page, source)
  await page.getByRole('button', { name: 'Outline', exact: true }).click()
  const outline = page.getByRole('navigation', { name: 'Document outline' })
  await expect(outline.getByRole('button')).toHaveCount(2)
  await outline.getByRole('button', { name: 'Nested {title} 9', exact: true }).click()
  await expect(page.getByText('Line 9 of 11', { exact: true })).toBeVisible()
  await expect(outline.getByRole('button', { name: 'Nested {title} 9', exact: true })).toHaveAttribute('aria-current', 'location')
  await selectLine(page, 9); await page.keyboard.insertText('\\subsection{Updated heading}')
  await expect(outline.getByRole('button', { name: 'Updated heading 9', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Editing guide', exact: true }).click()
  await expect(page.getByRole('region', { name: 'Editing guide', exact: true })).toContainText('Save with')
  await page.getByRole('button', { name: 'Close editing guide', exact: true }).click()
  await page.getByRole('button', { name: 'Expand editor', exact: true }).click()
  await expect(page.getByRole('region', { name: 'Document settings', exact: true })).toBeHidden()
  await expect(page.getByRole('button', { name: 'Save changes', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Build PDF', exact: true })).toBeEnabled()
  await page.locator('.editor-shell').screenshot({ path: testInfo.outputPath('source-editor.png') })
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1)
  await page.getByRole('button', { name: 'Exit expanded editor', exact: true }).click()
  await expect(page.getByRole('region', { name: 'Document settings', exact: true })).toBeVisible()
})

test('outline resumes after literal environment endings with trailing comments', async ({ page }) => {
  const lines = ['\\documentclass{article}', '\\begin{document}', '\\section{Before examples}']
  const environments = ['verbatim', 'verbatim*', 'Verbatim', 'lstlisting', 'minted', 'comment']
  for (const environment of environments) {
    lines.push(`\\begin{${environment}}`, '\\section{Example only}', `\\end{${environment === 'verbatim' ? 'comment' : 'verbatim'}}`,
      '\\section{Still inside example}', `  \\end{${environment}}${environment === 'comment' ? '' : '  '}% End of example`, `\\section{After ${environment}}`)
  }
  lines.push('\\end{document}')
  await setup(page, lines.join('\n'))
  await page.getByRole('button', { name: 'Outline', exact: true }).click()
  const outline = page.getByRole('navigation', { name: 'Document outline' })
  await expect(outline.getByRole('button')).toHaveCount(7)
  for (const [index, environment] of environments.entries()) {
    await expect(outline.getByRole('button', { name: `After ${environment} ${9 + index * 6}`, exact: true })).toBeVisible()
  }
  await outline.getByRole('button', { name: 'After comment 39', exact: true }).click()
  await expect(page.getByText('Line 39 of 40', { exact: true })).toBeVisible()
  await expect(editor(page)).toBeFocused()
})

test('outline keeps escaped percent titles and excludes headings interrupted by a comment', async ({ page }) => {
  const source = String.raw`\documentclass{article}
\begin{document}
\section{Progress \% complete}
\subsection{Nested {50\%} results}
\section{An escaped brace \} and \%}
\section{Unclosed % the closing brace is commented out}
\section{Line break \\% an unescaped comment hides the closing brace}
\section{Two backslashes and escaped percent \\\% complete}
\section{After comments}
\end{document}`
  await setup(page, source)
  await page.getByRole('button', { name: 'Outline', exact: true }).click()
  const outline = page.getByRole('navigation', { name: 'Document outline' })
  await expect(outline.getByRole('button')).toHaveCount(5)
  for (const title of ['Progress \\% complete 3', 'Nested {50\\%} results 4', 'An escaped brace \\} and \\% 5',
    'Two backslashes and escaped percent \\\\\\% complete 8', 'After comments 9']) {
    await expect(outline.getByRole('button', { name: title, exact: true })).toBeVisible()
  }
  await outline.getByRole('button', { name: 'Progress \\% complete 3', exact: true }).click()
  await expect(page.getByText('Line 3 of 10', { exact: true })).toBeVisible()
})

for (const mac of [false, true]) test(`${mac ? 'Cmd' : 'Ctrl'} Save/Build shortcuts obey modal and busy guards`, async ({ page }) => {
  const state = await setup(page, ORIGINAL, mac)
  const modifier = mac ? 'Meta' : 'Control'
  await editor(page).fill(ORIGINAL.replace('Original', 'Shortcut edit'))
  await page.getByRole('button', { name: 'All documents', exact: true }).click()
  await page.keyboard.press(`${modifier}+s`); await page.keyboard.press(`${modifier}+Enter`)
  expect(state.saves).toBe(0); expect(state.builds).toBe(0)
  await page.getByRole('dialog', { name: 'All documents' }).getByRole('button', { name: 'Close', exact: true }).click()
  let release!: () => void
  state.saveGate = new Promise<void>(resolve => { release = resolve })
  await editor(page).focus(); await page.keyboard.press(`${modifier}+s`)
  await expect(editor(page)).toBeDisabled()
  for (const name of ['Bold', 'Italic', 'Insert', 'Toggle comment']) await expect(page.getByRole('button', { name, exact: true })).toBeDisabled()
  await page.keyboard.press(`${modifier}+s`); await page.keyboard.press(`${modifier}+Enter`)
  expect(state.saves).toBe(1); expect(state.builds).toBe(0)
  release()
  await expect(editor(page)).toBeEnabled()
  await page.keyboard.press(`${modifier}+Enter`)
  await expect(page.getByText('Setting your words in place…', { exact: true })).toBeVisible()
  expect(state.builds).toBe(1); expect(state.saves).toBe(1)
  await page.getByRole('tab', { name: 'Source', exact: true }).click()
  await expect(editor(page)).toBeDisabled()
  await page.keyboard.press(`${modifier}+z`)
  await expect(editor(page)).toHaveText(ORIGINAL.replace('Original', 'Shortcut edit'), { useInnerText: true })
  await page.keyboard.press(`${modifier}+s`); await page.keyboard.press(`${modifier}+Enter`)
  expect(state.saves).toBe(1); expect(state.builds).toBe(1)
})

test('Thai and CJK composition does not trigger Save or Build shortcuts', async ({ page }) => {
  const state = await setup(page)
  await editor(page).focus(); await page.keyboard.press('Control+End')
  await editor(page).dispatchEvent('compositionstart', { data: '' })
  await page.keyboard.insertText(' ภาษาไทย 中文 日本語')
  await editor(page).dispatchEvent('keydown', { key: 's', code: 'KeyS', ctrlKey: true, isComposing: true })
  await editor(page).dispatchEvent('keydown', { key: 'Enter', code: 'Enter', ctrlKey: true, isComposing: true })
  expect(state.saves).toBe(0); expect(state.builds).toBe(0)
  await editor(page).dispatchEvent('compositionend', { data: ' ภาษาไทย 中文 日本語' })
  await expect(editor(page)).toContainText('ภาษาไทย 中文 日本語', { useInnerText: true })
  await page.getByRole('button', { name: 'Save changes', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Save changes', exact: true })).toBeDisabled()
  expect(state.source).toContain('ภาษาไทย 中文 日本語')
  expect(state.saves).toBe(1); expect(state.builds).toBe(0)
})

test('a source near 2 MiB stays editable, searchable and scrollable', async ({ page }, testInfo) => {
  test.setTimeout(60000)
  const header = '\\documentclass{article}\n\\begin{document}\n'
  const tail = '\nFINAL_MARKER\n\\end{document}\n'
  const target = 2 * 1024 * 1024 - 2048
  const body = ('% Synthetic large document line with ordinary text. '.repeat(2) + '\n').repeat(Math.ceil(target / 103)).slice(0, target - header.length - tail.length)
  const source = header + body + tail
  const state = await setup(page, source)
  const start = Date.now()
  await editor(page).focus(); await page.keyboard.press('Control+Home'); await page.keyboard.insertText('x')
  await expect(page.getByRole('button', { name: 'Save changes', exact: true })).toBeEnabled()
  const typingMs = Date.now() - start
  const searchStart = Date.now()
  await page.getByRole('button', { name: 'Find / replace', exact: true }).click()
  await page.getByRole('textbox', { name: 'Find', exact: true }).fill('FINAL_MARKER')
  await page.getByRole('button', { name: 'next', exact: true }).click()
  await expect(editor(page)).toContainText('FINAL_MARKER', { useInnerText: true })
  const searchMs = Date.now() - searchStart
  await page.keyboard.press('Control+s')
  await expect(page.getByRole('button', { name: 'Save changes', exact: true })).toBeDisabled()
  expect(state.source).toBe('x' + source)
  expect(await page.locator('.cm-scroller').evaluate(element => element.scrollTop)).toBeGreaterThan(0)
  const observations = { sourceBytes: Buffer.byteLength(source), typingMs, searchMs, project: testInfo.project.name,
    browser: page.context().browser()?.version(), note: 'Synthetic API source; event-to-visible timings include browser/test overhead, not a universal performance guarantee.' }
  await writeFile(testInfo.outputPath('large-document-observations.json'), JSON.stringify(observations, null, 2))
})
