import { lazy, Suspense, useState } from 'react'
import type { Ref } from 'react'
import type { LatexEditorHandle } from './LatexEditor'
import { ArrowRight, FileText, WarningCircle } from '@phosphor-icons/react'
import type { Job } from './api'
import { downloadUrl } from './api'

const PdfPreview = lazy(() => import('./PdfPreview'))
const LatexEditor = lazy(() => import('./LatexEditor').then(module => ({ default: module.LatexEditor })))

interface Props {
  job: Job | null
  source: string
  loading: boolean
  error: string
  onRetry: () => void
  readOnly: boolean
  view: 'source' | 'pdf'
  onSourceChange: (source: string) => void
  onBuild: () => void
  compiling: boolean
  saving: boolean
  dirty: boolean
  previewAvailable: boolean
  originalAvailable: boolean
  editorRef: Ref<LatexEditorHandle> | undefined
  commandKey: string
}

const example = [
  ['command', '\\documentclass{article}'], ['command', '\\usepackage{graphicx}'], ['', ''],
  ['comment', '% A new chapter starts here.'], ['command', '\\begin{document}'], ['', ''],
  ['command', '\\section{Your next great idea}'], ['text', 'A little less formatting.'],
  ['text', 'A little more room to think.'], ['', ''], ['math', 'E = mc^{2}'], ['', ''], ['command', '\\end{document}'],
]

export function SourceWorkspace({ job, source, loading, error, onRetry, readOnly, view, onSourceChange, onBuild, compiling, saving, dirty, previewAvailable, originalAvailable, editorRef, commandKey }: Props) {
  const [originalDownload, setOriginalDownload] = useState<{ id: string; busy: boolean; error: string } | null>(null)
  const currentDownload = originalDownload?.id === job?.id ? originalDownload : null
  const downloadOriginal = async () => {
    if (!job || currentDownload?.busy) return
    const id = job.id
    setOriginalDownload({ id, busy: true, error: '' })
    try {
      const response = await fetch(`/api/jobs/${id}/original`)
      if (!response.ok) {
        const detail = await response.json().catch(() => ({})) as { message?: string }
        throw new Error(detail.message || `Original download failed (${response.status}). Try again.`)
      }
      const url = URL.createObjectURL(await response.blob())
      downloadUrl(url, job.filename)
      setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch (error) {
      setOriginalDownload(previous => previous?.id === id ? { id, busy: false,
        error: error instanceof Error ? error.message : 'Could not download the original Word document.' } : previous)
    } finally { setOriginalDownload(previous => previous?.id === id ? { ...previous, busy: false } : previous) }
  }
  if (error) return <div className="preview-empty" role="alert"><WarningCircle size={32} /><h3>Could not load your source</h3><p>{error}</p><button className="secondary-button" onClick={onRetry}>Retry loading source<ArrowRight size={16} /></button>{job && originalAvailable && <button className="secondary-button" disabled={currentDownload?.busy} onClick={() => void downloadOriginal()}>{currentDownload?.busy ? 'Downloading original…' : 'Download original Word document'}<FileText size={16} /></button>}{currentDownload?.error && <p>{currentDownload.error}</p>}</div>
  if (loading) return <div className="editor-loading" aria-label="Preparing LaTeX source" aria-busy="true"><div className="loading-caption">Reading between the lines…</div>{[72, 45, 61, 0, 85, 68, 48, 0, 74, 39].map((width, index) => <div className="skeleton-row" key={index}><span>{index + 1}</span><i style={{ width: `${width}%`, animationDelay: `${index * 60}ms` }} /></div>)}</div>
  if (view === 'pdf') {
    if (job?.previewStatus === 'READY' && !dirty) return <Suspense fallback={<div className="preview-empty">Loading PDF viewer…</div>}><PdfPreview key={`${job.id}-${job.revision}`} id={job.id} revision={job.revision} /></Suspense>
    return <div className="preview-empty"><div className="preview-page"><FileText size={46} weight="light" /></div><span className="eyebrow">A FRESH PERSPECTIVE</span><h3>{compiling ? 'Setting your words in place…' : job?.previewStatus === 'FAILED' ? 'The PDF needs a little attention' : dirty ? 'Your source has changed.' : 'See your words on the page.'}</h3><p>{compiling ? job?.previewMessage : job?.previewStatus === 'FAILED' ? job.previewMessage : dirty ? 'Save your changes and build an updated PDF.' : 'Compile your saved LaTeX into a PDF, right here.'}</p>{compiling ? <div className="progress-track"><span /></div> : <button className="secondary-button" disabled={!job || job.status !== 'COMPLETED' || !source.trim() || !previewAvailable || saving || readOnly} onClick={onBuild}>{saving ? 'Saving changes…' : 'Build PDF preview'}<kbd className="shortcut-hint" aria-hidden="true">{commandKey} Enter</kbd><ArrowRight size={16} /></button>}{!previewAvailable && <small>PDF preview is available when Tectonic is installed.</small>}{!job && <small>Start by converting a document.</small>}{job?.previewStatus === 'FAILED' && <a className="compiler-log" href={`/api/jobs/${job.id}/preview/log`} target="_blank" rel="noreferrer"><WarningCircle size={15} />Open compiler log</a>}</div>
  }
  if (job?.status === 'COMPLETED') {
    return <Suspense fallback={<div className="editor-loading" role="status">Loading LaTeX editor…</div>}><LatexEditor key={job.id} commandKey={commandKey} editorRef={editorRef} source={source} disabled={compiling || saving || readOnly} onChange={onSourceChange} /></Suspense>
  }
  return <div className="example-output"><div className="example-caption"><span /> EXAMPLE OUTPUT</div><div className="example-code" aria-hidden="true">{example.map(([type, text], index) => <div key={index}><span className="example-line-number">{index + 1}</span><code className={`syntax-${type}`}>{text || ' '}</code></div>)}</div><div className="example-note"><span className="note-line" /><p>Your words.<br /><strong>A whole new form.</strong></p><span className="note-line" /></div></div>
}
