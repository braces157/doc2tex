import { useEffect, useId, useImperativeHandle, useLayoutEffect, useMemo, useRef, useState } from 'react'
import type { Ref } from 'react'
import { Compartment, EditorState } from '@codemirror/state'
import { drawSelection, EditorView, highlightActiveLine, highlightActiveLineGutter, keymap, lineNumbers } from '@codemirror/view'
import { defaultKeymap, history, historyKeymap, indentLess, indentMore, redo, redoDepth, toggleLineComment, undo, undoDepth } from '@codemirror/commands'
import { bracketMatching, defaultHighlightStyle, indentUnit, StreamLanguage, syntaxHighlighting } from '@codemirror/language'
import { autocompletion, closeBrackets, closeBracketsKeymap, completionKeymap } from '@codemirror/autocomplete'
import { openSearchPanel, search, searchKeymap } from '@codemirror/search'
import { stex } from '@codemirror/legacy-modes/mode/stex'
import { ArrowCounterClockwise, ArrowClockwise, ArrowElbowDownLeft, BracketsCurly, CaretDown, Code, List, MagnifyingGlass, Question, TextB, TextItalic, TextIndent, TextOutdent, X } from '@phosphor-icons/react'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger } from './components/ui/dropdown-menu'
import { documentHeadings, insertLatex, latexCompletions, latexTemplates } from './latexEditing'
import type { LatexTemplate } from './latexEditing'

export interface LatexEditorHandle {
  goToLine: (line: number) => number
  focus: () => void
  isComposing: () => boolean
  insertText: (text: string) => boolean
}

interface Props {
  source: string
  disabled: boolean
  onChange: (source: string) => void
  editorRef?: Ref<LatexEditorHandle>
  commandKey: string
}

export function LatexEditor({ source, disabled, onChange, editorRef, commandKey }: Props) {
  const host = useRef<HTMLDivElement>(null)
  const view = useRef<EditorView | null>(null)
  const factory = useRef<((source: string, disabled: boolean, wrap: boolean) => EditorState) | null>(null)
  const initial = useRef({ source, disabled })
  const callback = useRef(onChange)
  const pendingInsert = useRef<LatexTemplate | null>(null)
  const helpId = useId()
  const guideId = useId()
  const outlineId = useId()
  const [editable] = useState(() => new Compartment())
  const [wrapping] = useState(() => new Compartment())
  const [wrap, setWrap] = useState(false)
  const [showGuide, setShowGuide] = useState(false)
  const [showOutline, setShowOutline] = useState(false)
  const [lineInput, setLineInput] = useState('1')
  const [status, setStatus] = useState(() => ({ undo: false, redo: false, line: 1, column: 1, selected: 0, lines: source.split('\n').length }))
  const outline = useMemo(() => showOutline ? documentHeadings(source) : { items: [], truncated: false }, [source, showOutline])
  const activeHeading = outline.items.findLast(heading => heading.line <= status.line)?.line

  useLayoutEffect(() => { callback.current = onChange }, [onChange])

  const publish = (editor: EditorView) => {
    const selection = editor.state.selection.main
    const line = editor.state.doc.lineAt(selection.head)
    const next = { undo: undoDepth(editor.state) > 0, redo: redoDepth(editor.state) > 0,
      line: line.number, column: selection.head - line.from + 1, selected: selection.to - selection.from, lines: editor.state.doc.lines }
    setStatus(previous => Object.keys(next).every(key => next[key as keyof typeof next] === previous[key as keyof typeof next]) ? previous : next)
  }

  useEffect(() => {
    if (!host.current) return
    const create = (source: string, disabled: boolean, wrap: boolean) => EditorState.create({ doc: source, extensions: [
      lineNumbers(), highlightActiveLineGutter(), highlightActiveLine(), drawSelection(), history(), bracketMatching(), closeBrackets(),
      autocompletion({ override: [latexCompletions], defaultKeymap: false, interactionDelay: 0 }),
      EditorState.languageData.of((state, position) => {
        let start = position
        while (start > 0 && state.sliceDoc(start - 1, start) === '\\') start--
        // Escaped delimiters such as \\{ and \\( are LaTeX commands, not ordinary bracket pairs.
        return [{ closeBrackets: { brackets: (position - start) % 2 ? [] : ['(', '[', '{'] } }]
      }),
      keymap.of([
        { key: 'Mod-b', run: editor => insertLatex(editor, latexTemplates[0]) },
        { key: 'Mod-i', run: editor => insertLatex(editor, latexTemplates[1]) },
        { key: 'Mod-/', run: editor => !editor.state.readOnly && !editor.composing && toggleLineComment(editor) },
        ...closeBracketsKeymap, ...completionKeymap, ...historyKeymap, ...defaultKeymap, ...searchKeymap,
      ]),
      StreamLanguage.define(stex), syntaxHighlighting(defaultHighlightStyle), indentUnit.of('  '),
      search({ top: true, literal: true }),
      editable.of([EditorState.readOnly.of(disabled), EditorView.editable.of(!disabled),
        EditorView.contentAttributes.of({ 'aria-label': 'LaTeX source editor', 'aria-describedby': helpId,
          'aria-disabled': String(disabled), spellcheck: 'false' })]),
      wrapping.of(wrap ? EditorView.lineWrapping : []),
      EditorView.theme({ '&': { height: '100%', fontSize: '13px' }, '.cm-scroller': { overflow: 'auto', fontFamily: 'var(--font-mono)', lineHeight: '25px' },
        '.cm-content': { padding: '18px 0', color: '#3f4e35' }, '.cm-line': { padding: '0 16px' },
        '.cm-gutters': { backgroundColor: '#fafcf7', color: '#7d8a71', borderRight: '1px solid #edf1e6', minWidth: '44px' },
        '.cm-activeLine, .cm-activeLineGutter': { backgroundColor: '#f3f7ec' },
        '&.cm-focused': { outline: '2px solid #a1b981', outlineOffset: '-2px' },
        '.cm-selectionBackground, &.cm-focused .cm-selectionBackground': { backgroundColor: '#dceacb' },
        '.cm-matchingBracket': { backgroundColor: '#dfedce', outline: '1px solid #a1b981' },
        '.cm-nonmatchingBracket': { backgroundColor: '#f7e6dd', outline: '1px solid #b98267' } }),
      EditorView.updateListener.of(update => {
        if (update.docChanged) callback.current(update.state.doc.toString())
        if (update.docChanged || update.selectionSet) publish(update.view)
      }),
    ] })
    factory.current = create
    const editor = new EditorView({ state: create(initial.current.source, initial.current.disabled, false), parent: host.current })
    view.current = editor
    publish(editor)
    return () => { view.current = null; factory.current = null; editor.destroy() }
  }, [editable, wrapping, helpId])

  useEffect(() => {
    const editor = view.current
    if (!editor || !factory.current || editor.state.doc.toString() === source) return
    // A server load, draft restore or saved-version restore establishes a new baseline.
    // Echoes of local typing do not recreate state or reset undo history.
    editor.setState(factory.current(source, disabled, wrap))
    queueMicrotask(() => { if (view.current === editor) publish(editor) })
  }, [source, disabled, wrap])

  useEffect(() => {
    view.current?.dispatch({ effects: editable.reconfigure([EditorState.readOnly.of(disabled), EditorView.editable.of(!disabled),
      EditorView.contentAttributes.of({ 'aria-label': 'LaTeX source editor', 'aria-describedby': helpId, 'aria-disabled': String(disabled), spellcheck: 'false' })]) })
  }, [disabled, editable, helpId])

  useEffect(() => { view.current?.dispatch({ effects: wrapping.reconfigure(wrap ? EditorView.lineWrapping : []) }) }, [wrap, wrapping])

  const goToLine = (requested: number) => {
    const editor = view.current
    if (!editor) return 0
    const number = Number.isFinite(requested) ? Math.max(1, Math.min(editor.state.doc.lines, Math.trunc(requested))) : 1
    const line = editor.state.doc.line(number)
    editor.dispatch({ selection: { anchor: line.from }, effects: EditorView.scrollIntoView(line.from, { y: 'center' }) })
    editor.focus()
    return number
  }
  useImperativeHandle(editorRef, () => ({ goToLine, focus: () => view.current?.focus(), isComposing: () => view.current?.composing ?? false,
    insertText: text => {
      const editor = view.current
      if (!editor || editor.state.facet(EditorState.readOnly) || editor.composing) return false
      editor.dispatch({ ...editor.state.replaceSelection(text), scrollIntoView: true, userEvent: 'input' }); editor.focus(); return true
    } }))

  const command = (operation: (view: EditorView) => boolean) => {
    if (disabled || !view.current || view.current.composing) return false
    const applied = operation(view.current)
    view.current.focus()
    return applied
  }

  return <div className="latex-editor-shell">
    <div className="latex-editor-filebar"><span><BracketsCurly size={16} /><strong>main.tex</strong><span>LaTeX source</span></span><button aria-expanded={showGuide} aria-controls={guideId} onClick={() => setShowGuide(value => !value)}><Question size={16} />Editing guide</button></div>
    <div className="latex-editor-tools" role="group" aria-label="LaTeX editing tools">
      <div className="latex-tool-group">
        <button aria-label="Undo" title={`Undo (${commandKey} Z)`} disabled={disabled || !status.undo} onClick={() => command(undo)}><ArrowCounterClockwise size={16} /></button>
        <button aria-label="Redo" title={`Redo (${commandKey} Shift Z)`} disabled={disabled || !status.redo} onClick={() => command(redo)}><ArrowClockwise size={16} /></button>
      </div>
      <div className="latex-tool-group">
        <button aria-label="Bold" title={`Bold (${commandKey} B)`} disabled={disabled} onClick={() => command(editor => insertLatex(editor, latexTemplates[0]))}><TextB size={17} /></button>
        <button aria-label="Italic" title={`Italic (${commandKey} I)`} disabled={disabled} onClick={() => command(editor => insertLatex(editor, latexTemplates[1]))}><TextItalic size={17} /></button>
        <DropdownMenu><DropdownMenuTrigger asChild><button disabled={disabled}>Insert<CaretDown size={12} /></button></DropdownMenuTrigger><DropdownMenuContent align="start" onCloseAutoFocus={event => {
          const template = pendingInsert.current
          if (!template) return
          pendingInsert.current = null
          event.preventDefault()
          command(editor => insertLatex(editor, template))
        }}><DropdownMenuLabel>Add to your document</DropdownMenuLabel><DropdownMenuSeparator />{latexTemplates.slice(2).map(template => <DropdownMenuItem key={template.label} onSelect={() => { pendingInsert.current = template }}>{template.label}</DropdownMenuItem>)}</DropdownMenuContent></DropdownMenu>
        <button aria-label="Toggle comment" title={`Comment or uncomment selected lines (${commandKey} /)`} disabled={disabled} onClick={() => command(toggleLineComment)}><Code size={17} /></button>
      </div>
      <div className="latex-tool-group">
        <button aria-label="Indent" title={`Indent (${commandKey} ])`} disabled={disabled} onClick={() => command(indentMore)}><TextIndent size={17} /></button>
        <button aria-label="Outdent" title={`Outdent (${commandKey} [)`} disabled={disabled} onClick={() => command(indentLess)}><TextOutdent size={17} /></button>
      </div>
      <button onClick={() => { if (view.current) openSearchPanel(view.current) }}><MagnifyingGlass size={15} />Find / replace</button>
    </div>
    <div className="latex-editor-navigation">
      <button aria-expanded={showOutline} aria-controls={outlineId} onClick={() => setShowOutline(value => !value)}><List size={15} />Outline</button>
      <button aria-pressed={wrap} onClick={() => setWrap(value => !value)}><ArrowElbowDownLeft size={15} />Wrap</button>
      <form onSubmit={event => { event.preventDefault(); setLineInput(String(goToLine(Number(lineInput)))) }}>
        <label>Line<input type="number" step="any" aria-label="Go to line" value={lineInput} onChange={event => setLineInput(event.target.value)} /></label><button type="submit">Go</button>
      </form>
    </div>
    {showGuide && <section id={guideId} className="latex-editing-guide" aria-label="Editing guide"><div><strong>A few edits to make it yours</strong><button aria-label="Close editing guide" onClick={() => setShowGuide(false)}><X size={15} /></button></div><p>Edit your words directly. Select text, then use <b>Bold</b>, <i>Italic</i>, or <b>Insert</b> to add LaTeX around it. New placeholder text is selected so you can type over it.</p><dl><div><dt>Find a problem</dt><dd>Use Find / replace, or choose a source line in compiler diagnostics below after a PDF build.</dd></div><div><dt>Try a command</dt><dd>Type <code>\sec</code> for suggestions. Use arrow keys and Enter to choose; Escape closes the list.</dd></div><div><dt>Check your changes</dt><dd>Save with <kbd>{commandKey} S</kbd>. Build the PDF with <kbd>{commandKey} Enter</kbd>. Undo with <kbd>{commandKey} Z</kbd>; use Saved versions to recover an earlier save.</dd></div></dl></section>}
    <div className="latex-editor-body">
      {showOutline && <nav id={outlineId} className="latex-editor-outline" aria-label="Document outline"><strong>Document outline</strong>{outline.items.length ? <ol>{outline.items.map(heading => <li key={heading.line} className={heading.level > 2 ? 'is-subheading' : undefined}><button title={heading.title} aria-current={heading.line === activeHeading ? 'location' : undefined} onClick={() => goToLine(heading.line)}><span>{heading.title}</span><small>{heading.line}</small></button></li>)}</ol> : <p>Add a section with Insert. Your headings will appear here.</p>}{outline.truncated && <p>Showing the first 200 headings.</p>}</nav>}
      <div ref={host} className="latex-editor-host" />
    </div>
    <div className="latex-editor-position"><span>Line {status.line} of {status.lines}</span><span>Col {status.column}{status.selected > 0 && ` · ${status.selected} selected`}</span><span className="latex-editor-language">UTF-8 · LaTeX</span></div>
    <p id={helpId} className="latex-editor-help">Tab moves focus. {commandKey} B / I: bold / italic. {commandKey} /: comment. Type \ for commands.</p>
  </div>
}
