import type { Completion, CompletionContext } from '@codemirror/autocomplete'
import { isolateHistory } from '@codemirror/commands'
import { EditorState } from '@codemirror/state'
import type { EditorView } from '@codemirror/view'

export interface LatexTemplate {
  label: string
  command: string
  before: string
  after: string
  placeholder: string
  detail: string
  block?: boolean
}

export const latexTemplates: LatexTemplate[] = [
  { label: 'Bold', command: 'textbf', before: '\\textbf{', after: '}', placeholder: 'bold text', detail: 'Make selected words bold' },
  { label: 'Italic', command: 'textit', before: '\\textit{', after: '}', placeholder: 'italic text', detail: 'Make selected words italic' },
  { label: 'Section heading', command: 'section', before: '\\section{', after: '}', placeholder: 'Section title', detail: 'Add a main heading', block: true },
  { label: 'Subsection heading', command: 'subsection', before: '\\subsection{', after: '}', placeholder: 'Subsection title', detail: 'Add a heading inside a section', block: true },
  { label: 'Bulleted list', command: 'begin{itemize}', before: '\\begin{itemize}\n  \\item ', after: '\n\\end{itemize}', placeholder: 'First item', detail: 'Add an unordered list', block: true },
  { label: 'Numbered list', command: 'begin{enumerate}', before: '\\begin{enumerate}\n  \\item ', after: '\n\\end{enumerate}', placeholder: 'First item', detail: 'Add an ordered list', block: true },
  { label: 'Inline equation', command: '(', before: '\\(', after: '\\)', placeholder: 'a^2 + b^2 = c^2', detail: 'Put math inside a sentence' },
  { label: 'Display equation', command: '[', before: '\\[\n  ', after: '\n\\]', placeholder: 'E = mc^2', detail: 'Put math on its own line', block: true },
  { label: 'Footnote', command: 'footnote', before: '\\footnote{', after: '}', placeholder: 'Footnote text', detail: 'Add a note at the foot of the page' },
]

/** Use a single undoable edit, and select the inserted text so it can be replaced immediately. */
export function insertLatex(view: EditorView, template: LatexTemplate, completionRange?: { from: number; to: number }): boolean {
  if (view.state.facet(EditorState.readOnly) || view.composing) return false
  const { from, to } = completionRange ?? view.state.selection.main
  const text = completionRange ? template.placeholder : view.state.sliceDoc(from, to) || template.placeholder
  const leading = template.block && from > 0 && view.state.doc.lineAt(from).from !== from ? '\n\n' : ''
  const trailing = template.block && to < view.state.doc.length && view.state.doc.lineAt(to).to !== to ? '\n' : ''
  const before = leading + template.before
  view.dispatch({ changes: { from, to, insert: before + text + template.after + trailing },
    selection: { anchor: from + before.length, head: from + before.length + text.length },
    annotations: isolateHistory.of('full'), userEvent: 'input', scrollIntoView: true })
  view.focus()
  return true
}

const completions: Completion[] = latexTemplates.map(template => ({
  label: `\\${template.command}`, type: 'function', detail: template.label, info: template.detail,
  apply: (view, _completion, from, to) => { insertLatex(view, template, { from, to }) },
}))

export function latexCompletions(context: CompletionContext) {
  const match = context.matchBefore(/\\[a-zA-Z]*/)
  if (!match || match.from === match.to || match.text.length < 2 && !context.explicit) return null
  const line = context.state.doc.lineAt(context.pos)
  // A percent sign starts a comment unless escaped. Don't suggest commands in comments.
  if (/(^|[^\\])(?:\\\\)*%/.test(line.text.slice(0, context.pos - line.from))) return null
  return { from: match.from, options: completions, validFor: /^\\[a-zA-Z]*$/ }
}

export interface OutlineHeading { line: number; level: number; title: string }
const headingLevels = ['part', 'chapter', 'section', 'subsection', 'subsubsection']

/** A navigation aid for conventional headings, not a TeX interpreter. */
export function documentHeadings(source: string): { items: OutlineHeading[]; truncated: boolean } {
  const items: OutlineHeading[] = []
  let literal: string | undefined
  const lines = source.split('\n')
  for (let index = 0; index < lines.length; index++) {
    const line = lines[index]
    if (literal) {
      if (line.trim() === `\\end{${literal}}`) literal = undefined
      continue
    }
    const environment = /^\s*\\begin\{(verbatim\*?|Verbatim|lstlisting|minted|comment)\}/.exec(line)
    if (environment) { literal = environment[1]; continue }
    const match = /^\s*\\(part|chapter|section|subsection|subsubsection)\*?(?:\[[^\]]*\])?\s*\{/.exec(line)
    if (!match) continue
    let depth = 1, end = match[0].length
    for (; end < line.length; end++) {
      if (line[end] === '\\') { end++; continue }
      if (line[end] === '%') break
      if (line[end] === '{') depth++
      if (line[end] === '}' && --depth === 0) break
    }
    if (depth !== 0) continue
    if (items.length === 200) return { items, truncated: true }
    items.push({ line: index + 1, level: headingLevels.indexOf(match[1]), title: line.slice(match[0].length, end).trim() || 'Untitled heading' })
  }
  return { items, truncated: false }
}
