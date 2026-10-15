import { useEffect, useId, useImperativeHandle, useLayoutEffect, useRef, useState } from 'react'
import type { Ref } from 'react'
import { Compartment, EditorState } from '@codemirror/state'
import { EditorView, highlightActiveLine, highlightActiveLineGutter, keymap, lineNumbers } from '@codemirror/view'
import { defaultKeymap, history, historyKeymap, indentLess, indentMore, redo, redoDepth, undo, undoDepth } from '@codemirror/commands'
import { defaultHighlightStyle, indentUnit, StreamLanguage, syntaxHighlighting } from '@codemirror/language'
import { openSearchPanel, search, searchKeymap } from '@codemirror/search'
import { stex } from '@codemirror/legacy-modes/mode/stex'

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
  const helpId = useId()
  const [editable] = useState(() => new Compartment())
  const [wrapping] = useState(() => new Compartment())
  const [wrap, setWrap] = useState(false)
  const [lineInput, setLineInput] = useState('1')
  const [status, setStatus] = useState(() => ({ undo: false, redo: false, line: 1, lines: source.split('\n').length }))

  useLayoutEffect(() => { callback.current = onChange }, [onChange])

  const publish = (editor: EditorView) => {
    const next = { undo: undoDepth(editor.state) > 0, redo: redoDepth(editor.state) > 0,
      line: editor.state.doc.lineAt(editor.state.selection.main.head).number, lines: editor.state.doc.lines }
    setStatus(previous => Object.keys(next).every(key => next[key as keyof typeof next] === previous[key as keyof typeof next]) ? previous : next)
  }

  useEffect(() => {
    if (!host.current) return
    const create = (source: string, disabled: boolean, wrap: boolean) => EditorState.create({ doc: source, extensions: [
      lineNumbers(), highlightActiveLineGutter(), highlightActiveLine(), history(),
      keymap.of([...historyKeymap, ...defaultKeymap, ...searchKeymap]),
      StreamLanguage.define(stex), syntaxHighlighting(defaultHighlightStyle), indentUnit.of('  '),
      search({ top: true, literal: true }),
      editable.of([EditorState.readOnly.of(disabled), EditorView.editable.of(!disabled),
        EditorView.contentAttributes.of({ 'aria-label': 'LaTeX source editor', 'aria-describedby': helpId,
          'aria-disabled': String(disabled), spellcheck: 'false' })]),
      wrapping.of(wrap ? EditorView.lineWrapping : []),
      EditorView.theme({ '&': { height: '100%', fontSize: '12px' }, '.cm-scroller': { overflow: 'auto', fontFamily: 'var(--font-mono)', lineHeight: '24px' },
        '.cm-content': { padding: '16px 0', color: '#486039' }, '.cm-line': { padding: '0 12px' },
        '.cm-gutters': { backgroundColor: '#fcfdf9', color: '#829473', borderRight: '1px solid #edf1e6' },
        '.cm-activeLine, .cm-activeLineGutter': { backgroundColor: '#f3f7ec' },
        '&.cm-focused': { outline: '2px solid #a1b981', outlineOffset: '-2px' },
        '.cm-selectionBackground, &.cm-focused .cm-selectionBackground': { backgroundColor: '#dceacb' } }),
      EditorView.updateListener.of(update => {
        if (update.docChanged) callback.current(update.state.doc.toString())
        if (update.docChanged || update.selectionSet) publish(update.view)
      }),
    ] })
    factory.current = create
    const editor = new EditorView({ state: create(initial.current.source, initial.current.disabled, false), parent: host.current })
    view.current = editor
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
    if (disabled || !view.current) return
    operation(view.current); view.current.focus()
  }

  return <div className="latex-editor-shell">
    <div className="latex-editor-tools" aria-label="LaTeX editing tools">
      <button disabled={disabled || !status.undo} onClick={() => command(undo)}>Undo</button>
      <button disabled={disabled || !status.redo} onClick={() => command(redo)}>Redo</button>
      <button onClick={() => { if (view.current) openSearchPanel(view.current) }}>Find / replace</button>
      <button disabled={disabled} onClick={() => command(indentMore)}>Indent</button>
      <button disabled={disabled} onClick={() => command(indentLess)}>Outdent</button>
      <button aria-pressed={wrap} onClick={() => setWrap(value => !value)}>Wrap</button>
      <form onSubmit={event => { event.preventDefault(); setLineInput(String(goToLine(Number(lineInput)))) }}>
        <label>Line<input type="number" step="any" aria-label="Go to line" value={lineInput} onChange={event => setLineInput(event.target.value)} /></label><button type="submit">Go</button>
      </form>
    </div>
    <p id={helpId} className="latex-editor-help">Tab moves focus. Indent / Outdent: {commandKey} ] / {commandKey} [. <span>Line {status.line} of {status.lines}</span></p>
    <div ref={host} className="latex-editor-host" />
  </div>
}
