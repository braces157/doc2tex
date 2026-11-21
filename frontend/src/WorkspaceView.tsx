import { lazy, Suspense, useRef, useState } from 'react'
import type { Ref } from 'react'
import type { LatexEditorHandle } from '@/LatexEditor'
import { ArrowDown, ArrowRight, ArrowUpRight, ArrowsInSimple, ArrowsLeftRight, ArrowsOutSimple, BracketsCurly, Check, CheckCircle, Clock,
  Copy, DotsThree, FileDoc, FilePdf, FileText, FolderSimple, GitDiff, Info, ListChecks, Plus,
  UploadSimple, WarningCircle, X } from '@phosphor-icons/react'
import { toast } from 'sonner'
import type { ConversionReview as Review, Job, LayoutOptions, SystemStatus } from '@/api'
import { downloadUrl, formatBytes, jobTitle, validLayout } from '@/api'
import { exportJobFile } from '@/ecosystemApi'
import { downloadBlob } from '@/pdfApi'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { Progress } from '@/components/ui/progress'
import { SourceWorkspace } from '@/SourceWorkspace'
import { CompilerDiagnosticsPanel } from '@/components/CompilerDiagnosticsPanel'
import { OriginalTextPanel } from '@/components/OriginalTextPanel'
import { DraftRecovery } from '@/components/DraftRecovery'
import { ReadinessBanner } from '@/components/ReadinessBanner'
import type { Draft } from '@/drafts'
const DocumentLibrary = lazy(() => import('@/DocumentLibrary').then(module => ({ default: module.DocumentLibrary })))
const VersionHistory = lazy(() => import('@/VersionHistory').then(module => ({ default: module.VersionHistory })))
const ConversionReview = lazy(() => import('@/components/ConversionReview').then(module => ({ default: module.ConversionReview })))
const CaptionRepairsDialog = lazy(() => import('@/CaptionRepairsDialog').then(module => ({ default: module.CaptionRepairsDialog })))
const LayoutSettings = lazy(() => import('@/LayoutSettings').then(module => ({ default: module.LayoutSettings })))
const BibliographyPanel = lazy(() => import('@/BibliographyPanel').then(module => ({ default: module.BibliographyPanel })))
const SetupDiagnostics = lazy(() => import('@/SetupDiagnostics').then(module => ({ default: module.SetupDiagnostics })))
const PdfStudio = lazy(() => import('@/PdfStudio'))
const FormatHub = lazy(() => import('@/FormatHub'))
const CompareStudio = lazy(() => import('@/CompareStudio'))

export interface WorkspaceModel {
  system: SystemStatus | null; setSystem: (status: SystemStatus) => void; history: Job[]; job: Job | null; file: File | null; template: string
  source: string; sourceLoading: boolean; error: string; saving: boolean; dragging: boolean
  copied: boolean; mode: 'convert' | 'pdf' | 'hub' | 'compare'; setMode: (mode: 'convert' | 'pdf' | 'hub' | 'compare') => void
  view: 'source' | 'pdf' | 'review'; dirty: boolean; converting: boolean; compiling: boolean
  switchingDisabled: boolean; completed: boolean; readyForActions: boolean; review: Review | null
  autoPrepare: boolean; setAutoPrepare: (value: boolean) => void
  selectFile: (file: File | undefined) => void; setDragging: (value: boolean) => void
  setTemplate: (value: string) => void; setView: (value: 'source' | 'pdf' | 'review') => void
  setSource: (value: string) => void; setError: (value: string) => void
  reset: () => void; openJob: (job: Job) => void; trySample: () => void; startConversion: () => void
  save: () => void; copy: () => void; download: () => void; downloadTex: () => void; buildPreview: () => void; verifyPreview: () => void
  sourceError: string; storageError: string; recovery: Draft | null; sourceRevision: number | undefined
  retrySource: () => void; restoreDraft: () => void; discardDraft: () => void
  missingDocument: boolean; renamed: (job: Job) => void; deleted: (job: Job) => void
  versionsAvailable: boolean; restoreVersion: (revision: number) => Promise<boolean>
  editorRef: Ref<LatexEditorHandle>; commandKey: string
  navigateSource: (line: number, revision: number) => void
  reviewSaving: boolean; reviewError: string; retryReview: () => void
  decideFinding: (id: string, status: 'reviewed' | 'needs-work' | 'unreviewed') => void
  applyCaptionRepairs: (revision: number, sourceHash: string, ids: string[]) => Promise<boolean>
  layout: LayoutOptions; setLayout: (value: LayoutOptions) => void; copyLayout: () => Promise<boolean>
  bibliographySaving: boolean; importBibliography: (file: File) => Promise<boolean>; setupBibliography: (revision: number, hash: string) => Promise<boolean>
  insertCitation: (key: string, revision: number) => void
}

export function WorkspaceView({ model: m }: { model: WorkspaceModel }) {
  const fileInput = useRef<HTMLInputElement>(null)
  const [guideOpen, setGuideOpen] = useState(false)
  const [focusedJob, setFocusedJob] = useState<string | null>(null)
  const editorFocused = Boolean(m.job && focusedJob === m.job.id)
  const [setupOpen, setSetupOpen] = useState(false)
  const [historyOpen, setHistoryOpen] = useState(false)
  const [libraryOpen, setLibraryOpen] = useState(false)
  const [versionsOpen, setVersionsOpen] = useState(false)
  const [captionRepairsOpen, setCaptionRepairsOpen] = useState(false)
  const [layoutOpen, setLayoutOpen] = useState(false)
  const [bibliographyOpen, setBibliographyOpen] = useState(false)
  const [originalSelection, setOriginalSelection] = useState<{ jobId: string; id: string } | null>(null)
  const originalItem = originalSelection?.jobId === m.job?.id ? m.review?.items?.find(item => item.id === originalSelection?.id) : undefined
  const comparing = Boolean(originalItem && m.view !== 'review')
  const compareOriginal = (id: string) => { if (m.job) { setOriginalSelection({ jobId: m.job.id, id }); m.setView('source') } }
  const canReview = m.readyForActions && !m.dirty && !m.saving && !m.compiling && !m.reviewSaving && m.review?.revision === m.job?.revision && !m.review?.decisionError && !m.reviewError
  const warningCount = (m.review?.findings.filter(item => item.severity === 'warning').length ?? 0) + (m.review?.compilerWarnings.length ?? 0)
  const reviewActionCount = m.review?.readiness ? m.review.readiness.actions.length : warningCount
  const activeFilename = m.file?.name ?? (m.job ? jobTitle(m.job) : undefined)
  const compactSource = Boolean(m.job && !m.file)
  const templateLabel = m.template === 'report' ? 'Report' : 'Article'
  return <div className="ui-workspace">
    <a className="skip-link" href="#workspace-main">Skip to conversion workspace</a>
    <aside className="ui-sidebar">
      <button onClick={m.reset} className="ui-brand" aria-label="doc2TeX home"><svg className="ui-brand-mark" viewBox="0 0 48 48" width="28" height="28" aria-hidden="true"><rect width="48" height="48" rx="9" fill="#1f4d3a"/><path d="M22 11.5h-8a2.5 2.5 0 0 0-2.5 2.5v20a2.5 2.5 0 0 0 2.5 2.5h8" fill="none" stroke="#f5f3ee" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round"/><path d="M16.5 19h6.5M16.5 29h6.5" stroke="#d5e6de" strokeWidth="2.4" strokeLinecap="round"/><path d="M16.5 24h5" stroke="#e08e45" strokeWidth="2.4" strokeLinecap="round"/><path d="M27.5 11.5c2.8 0 4 1.5 4 4.2v4.3c0 2.3 1.3 3.5 3.8 4-2.5.5-3.8 1.7-3.8 4v4.3c0 2.7-1.2 4.2-4 4.2" fill="none" stroke="#f5f3ee" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round"/></svg><span className="ui-brand-name"><strong>doc<span>2</span>TeX</strong><small>Document &amp; PDF Studio</small></span></button>
      <nav className="ui-studio-nav" aria-label="Studio tools">
        <button type="button" aria-current={m.mode === 'convert' ? 'page' : undefined} onClick={() => m.setMode('convert')}><FileDoc size={14} />DOCX → LaTeX</button>
        <button type="button" aria-current={m.mode === 'pdf' ? 'page' : undefined} onClick={() => m.setMode('pdf')}><FilePdf size={14} />PDF Studio</button>
        <button type="button" aria-current={m.mode === 'hub' ? 'page' : undefined} onClick={() => m.setMode('hub')}><ArrowsLeftRight size={14} />Format Hub</button>
        <button type="button" aria-current={m.mode === 'compare' ? 'page' : undefined} onClick={() => m.setMode('compare')}><GitDiff size={14} />Compare &amp; Clean</button>
      </nav>
      <Button className="ui-new-button" onClick={m.reset} disabled={m.switchingDisabled}><Plus size={15} weight="bold" />New conversion</Button>
      <div className="ui-recent-label"><span>Recent</span><span>{m.history.length}</span></div>
      <nav className="ui-recent-list" aria-label="Recent conversions">
        {m.history.length ? m.history.slice(0,10).map(job => <button key={job.id} disabled={m.switchingDisabled} onClick={() => m.openJob(job)} className={`ui-recent-file ${m.job?.id === job.id ? 'active' : ''}`}><FileText size={16} /><span><strong>{jobTitle(job)}</strong><small>{job.status === 'COMPLETED' ? 'Ready to edit' : job.status === 'FAILED' ? 'Conversion failed' : 'Converting…'}</small></span>{job.status !== 'COMPLETED' && <span className={`ui-recent-dot ${job.status === 'FAILED' ? 'failed' : 'working'}`} aria-hidden="true" />}</button>) : <div className="ui-recent-empty"><FolderSimple size={22} /><p>No conversions yet.<br />Converted documents appear here.</p></div>}
      </nav>
      <div className="ui-sidebar-bottom"><p className="ui-local-caption">Local · files stay on this machine</p><Button variant="ghost" className="ui-guide-button" onClick={() => setGuideOpen(true)}><Info size={16} />Conversion guide<ArrowUpRight size={14} /></Button><span className="ui-version">doc2TeX <span>v0.2</span></span></div>
    </aside>
    <main className="ui-main" id="workspace-main">
      <header className="ui-topbar"><div><span>Workspace</span><span className="ui-breadcrumb-divider">/</span><strong>{m.mode === 'pdf' ? 'PDF Studio' : m.mode === 'hub' ? 'Format Hub' : m.mode === 'compare' ? 'Compare & Clean' : m.job ? jobTitle(m.job) : 'New conversion'}</strong></div><div><Button className="ui-library-trigger" variant="ghost" onClick={() => setLibraryOpen(true)}><FolderSimple size={16} />All documents</Button><Button className="ui-mobile-history" variant="ghost" size="icon-sm" aria-label="Recent documents" onClick={() => setHistoryOpen(true)}><Clock size={17} /></Button><Tooltip><TooltipTrigger asChild><Button variant="outline" aria-label="Setup diagnostics" onClick={() => setSetupOpen(true)} className="ui-system-status"><span className={m.system?.pandoc ? 'available' : m.system ? 'missing' : ''} />{m.system?.pandoc ? 'Ready to convert' : m.system ? 'Setup needed' : 'Connecting'}</Button></TooltipTrigger><TooltipContent>{m.system?.pandoc ? 'The document converter is available on this machine.' : m.system ? 'Open Setup diagnostics to install or repair the document converter.' : 'Checking the local conversion service.'}</TooltipContent></Tooltip></div></header>
      <nav className="ui-mobile-studio-nav" aria-label="Studio tools mobile">
        <button type="button" aria-current={m.mode === 'convert' ? 'page' : undefined} onClick={() => m.setMode('convert')}><FileDoc size={14} />DOCX → LaTeX</button>
        <button type="button" aria-current={m.mode === 'pdf' ? 'page' : undefined} onClick={() => m.setMode('pdf')}><FilePdf size={14} />PDF Studio</button>
        <button type="button" aria-current={m.mode === 'hub' ? 'page' : undefined} onClick={() => m.setMode('hub')}><ArrowsLeftRight size={14} />Format Hub</button>
        <button type="button" aria-current={m.mode === 'compare' ? 'page' : undefined} onClick={() => m.setMode('compare')}><GitDiff size={14} />Compare &amp; Clean</button>
      </nav>
      <div className="ui-content">
        {m.mode === 'pdf' && <Suspense fallback={<p role="status">Loading PDF Studio…</p>}><PdfStudio history={m.history} /></Suspense>}
        {m.mode === 'hub' && <Suspense fallback={<p role="status">Loading Format Hub…</p>}><FormatHub tectonicReady={m.system?.tectonic ?? false} /></Suspense>}
        {m.mode === 'compare' && <Suspense fallback={<p role="status">Loading Compare &amp; Clean…</p>}><CompareStudio /></Suspense>}
        <div className="ui-mode-pane" hidden={m.mode !== 'convert'}>
        {!m.job && <section className="ui-intro"><div><h1>Convert a Word document to LaTeX</h1><p>Upload a .docx file and get editable, compilable LaTeX source with headings, equations, lists, footnotes, and figures carried over.</p></div></section>}
        {m.system && !m.system.pandoc && <div className="ui-notice"><Info size={19} /><p><strong>Install the document converter.</strong> Open Setup diagnostics for installation and recovery steps.</p></div>}
        {m.error && <div className="ui-error" role="alert"><WarningCircle size={20} /><p>{m.error}</p><Button variant="ghost" size="icon-sm" aria-label="Dismiss error" onClick={() => m.setError('')}><X size={16} /></Button></div>}
        <div className={`ui-workbench ${compactSource ? 'has-job' : ''} ${editorFocused ? 'is-editor-focused' : ''}`}>
          {m.missingDocument && <section className="ui-notice ui-missing-document" role="alert" aria-label="Document unavailable"><div><strong>This document was removed or is awaiting deletion cleanup.</strong><p>Save, build, and export are unavailable. Your text in this tab is still here; download it before leaving. To retry cleanup, open All documents.</p></div><Button variant="outline" onClick={m.downloadTex} disabled={m.sourceRevision === undefined}>Download unsaved text</Button></section>}
          <section className={`ui-input-panel ${compactSource ? 'is-compact' : ''}`} aria-label="Document settings">
            <input ref={fileInput} type="file" className="sr-only" aria-label="Upload Word document" accept=".docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document" disabled={m.converting} onChange={event => { m.selectFile(event.target.files?.[0]); event.target.value = '' }} />
            {compactSource && m.job ? <>
              <div className="ui-source-summary">
                <div className="ui-source-meta">
                  <FileDoc size={18} className="ui-source-icon" />
                  <div className="ui-source-title">
                    <strong>{activeFilename}</strong>
                    <span>{formatBytes(m.job.size)} · {templateLabel}</span>
                  </div>
                  <Badge variant="outline" className={`ui-source-badge ${m.job.status === 'FAILED' ? 'failed' : m.completed ? 'ready' : 'working'}`}>
                    {m.completed ? 'Converted' : m.job.status === 'FAILED' ? 'Failed' : 'Converting…'}
                  </Badge>
                </div>
                <div className="ui-source-actions">
                  <Button variant="outline" size="sm" disabled={m.converting} onClick={() => fileInput.current?.click()}><UploadSimple size={14} />Choose another document</Button>
                </div>
              </div>
              <details className="ui-layout-options" open={layoutOpen} onToggle={event => setLayoutOpen(event.currentTarget.open)}><summary>Layout options</summary>{layoutOpen && <Suspense fallback={<p role="status">Loading layout options…</p>}><LayoutSettings layout={m.layout} onChange={m.setLayout} template={m.template} onTemplate={m.setTemplate} job={m.job} disabled={m.converting || m.saving} canCopy={m.readyForActions && !m.dirty && !m.compiling && !m.saving && m.system?.pandoc !== false} dirty={m.dirty} onCopy={m.copyLayout} /></Suspense>}</details>
              {(m.converting || m.job.status === 'FAILED') && <div className={`ui-job-status ${m.job.status === 'FAILED' ? 'failed' : 'working'}`} role="status"><div>{m.job.status === 'FAILED' ? <WarningCircle size={18} weight="fill" /> : <Clock size={18} />}<strong>{m.job.status === 'FAILED' ? 'Conversion failed' : 'Converting document…'}</strong></div><p>{m.job.message}</p>{m.converting && <Progress className="ui-job-progress" />}</div>}
            </> : <>
              <div className="ui-panel-heading"><h2>Source document</h2><span>Word .docx</span></div>
              <div className={`ui-dropzone ${m.dragging ? 'is-dragging' : ''} ${activeFilename ? 'is-selected' : ''}`} onDragOver={event => { event.preventDefault(); if (!m.converting) m.setDragging(true) }} onDragLeave={() => m.setDragging(false)} onDrop={event => { event.preventDefault(); m.setDragging(false); if (!m.converting) m.selectFile(event.dataTransfer.files[0]) }}>
                <div className="ui-upload-icon"><FileDoc size={26} /><span>DOCX</span></div>
                <h3>{activeFilename || 'Drop a Word document here'}</h3><p>{m.file ? `${formatBytes(m.file.size)} · Ready to convert` : m.job ? `${formatBytes(m.job.size)} · Saved conversion` : 'or browse your files to choose one'}</p>
                <Button variant="outline" className="ui-choose-button" disabled={m.converting} onClick={() => fileInput.current?.click()}><UploadSimple size={15} />{activeFilename ? 'Choose another document' : 'Choose a document'}</Button><span className="ui-upload-limit">.docx only <span>·</span> up to 20 MB</span>
              </div>
              {!m.file && <button className="ui-sample-link" onClick={m.trySample} disabled={m.converting || m.saving || m.system?.pandoc === false}>Try it with a sample document<ArrowRight size={13} /></button>}
              <div className="ui-template-label"><h3>Document class</h3><span>LaTeX template</span></div>
              <RadioGroup value={m.template} onValueChange={m.setTemplate} disabled={m.converting} className="ui-template-list" aria-label="LaTeX template">{[{id:'article',title:'Article',detail:'Papers, essays, short documents',icon:FileText},{id:'report',title:'Report',detail:'Chapters, theses, long documents',icon:FolderSimple}].map(item => <label key={item.id} htmlFor={`template-${item.id}`} className={`ui-template ${m.template === item.id ? 'selected' : ''}`}><item.icon size={18} /><span><strong>{item.title}</strong><small>{item.detail}</small></span><RadioGroupItem id={`template-${item.id}`} value={item.id} aria-label={item.title} /></label>)}</RadioGroup>
              <details className="ui-layout-options" open={layoutOpen} onToggle={event => setLayoutOpen(event.currentTarget.open)}><summary>Layout options</summary>{layoutOpen && <Suspense fallback={<p role="status">Loading layout options…</p>}><LayoutSettings layout={m.layout} onChange={m.setLayout} template={m.template} onTemplate={m.setTemplate} job={m.job} disabled={m.converting || m.saving} canCopy={m.readyForActions && !m.dirty && !m.compiling && !m.saving && m.system?.pandoc !== false} dirty={m.dirty} onCopy={m.copyLayout} /></Suspense>}</details>
              <label className="ui-auto-prepare"><input type="checkbox" checked={m.autoPrepare} disabled={m.converting} onChange={event => m.setAutoPrepare(event.target.checked)} /><span><strong>Auto-fix &amp; verify PDF</strong><small>Fix common issues, build the PDF, and check readiness automatically.</small></span></label>
              <Button className="ui-convert-button" disabled={!m.file || m.converting || m.saving || m.system?.pandoc === false || !validLayout(m.layout)} onClick={m.startConversion}>{m.converting ? 'Converting…' : 'Convert to LaTeX'}<ArrowRight size={16} weight="bold" /></Button>
              {m.job && <div className={`ui-job-status ${m.job.status === 'FAILED' ? 'failed' : m.completed ? '' : 'working'}`} role="status"><div>{m.completed ? <CheckCircle size={18} weight="fill" /> : m.job.status === 'FAILED' ? <WarningCircle size={18} weight="fill" /> : <Clock size={18} />}<strong>{m.completed ? 'Conversion complete' : m.job.status === 'FAILED' ? 'Conversion failed' : 'Converting document…'}</strong></div><p>{m.job.message}</p>{m.converting && <Progress className="ui-job-progress" />}</div>}
              <div className="ui-input-note"><Info size={15} /><p>Best results with Word heading styles and native equations. Complex layouts may need manual edits.</p></div>
            </>}
          </section>
          <section className="ui-output-panel" aria-label="LaTeX output"><div className="ui-output-header"><div className="ui-panel-heading"><h2>LaTeX output</h2><Badge variant="outline" className="ui-output-state" data-tone={m.sourceError ? 'error' : m.sourceLoading || m.converting ? 'busy' : m.completed ? m.dirty ? 'unsaved' : 'ready' : 'idle'}>{m.sourceError ? 'Source unavailable' : m.sourceLoading ? 'Loading source' : m.completed ? m.dirty ? 'Unsaved changes' : 'Ready to edit' : m.converting ? 'Converting' : 'Waiting for document'}</Badge></div><div className="ui-output-actions"><div><Button variant="outline" disabled={!m.versionsAvailable || m.compiling || m.saving} onClick={() => { toast.dismiss(); setVersionsOpen(true) }}>Saved versions</Button><Button variant="outline" onClick={m.save} disabled={!m.readyForActions || !m.dirty || m.saving || m.compiling}>{m.saving ? 'Saving…' : 'Save changes'}<kbd className="shortcut-hint" aria-hidden="true">{m.commandKey} S</kbd></Button><div className="ui-download-group"><Button className="ui-download-main" disabled={!m.readyForActions || m.saving || (m.compiling && m.dirty)} onClick={m.download}><ArrowDown size={15} weight="bold" />Download ZIP</Button><DropdownMenu><DropdownMenuTrigger asChild><Button className="ui-download-options" aria-label="More download options" disabled={!m.readyForActions}><DotsThree size={19} weight="bold" /></Button></DropdownMenuTrigger><DropdownMenuContent align="end"><DropdownMenuLabel>Export</DropdownMenuLabel><DropdownMenuSeparator /><DropdownMenuItem onSelect={m.downloadTex}><BracketsCurly size={16} />Current LaTeX source</DropdownMenuItem><DropdownMenuItem disabled={m.job?.previewStatus !== 'READY' || m.dirty} onSelect={() => { if (m.job) downloadUrl(`/api/jobs/${m.job.id}/preview`, 'preview.pdf') }}><FileText size={16} />Saved PDF</DropdownMenuItem><DropdownMenuSeparator /><DropdownMenuLabel>Convert edited LaTeX to…</DropdownMenuLabel>{([['docx', 'docx', 'Word (.docx)'], ['markdown', 'md', 'Markdown (.md)'], ['html', 'html', 'HTML (.html)'], ['epub', 'epub', 'EPUB (.epub)'], ['typst', 'typ', 'Typst (.typ)'], ['pptx', 'pptx', 'PowerPoint (.pptx)']] as const).map(([fmt, ext, label]) => <DropdownMenuItem key={fmt} disabled={!m.readyForActions || m.dirty || m.saving || m.system?.pandoc === false} onSelect={() => { if (!m.job) return; const fallback = `${jobTitle(m.job).replace(/\.[^.]+$/, '')}.${ext}`; void exportJobFile(m.job.id, fmt, fallback).then(dl => { downloadBlob(dl.blob, dl.filename); if (dl.warnings) toast.warning(`Exported ${dl.filename} with notes: ${dl.warnings}`); else toast.success(`Exported ${dl.filename}`) }).catch((err: unknown) => toast.error(err instanceof Error ? err.message : `Could not export to ${label}.`)) }}><ArrowsLeftRight size={16} />{label}</DropdownMenuItem>)}<DropdownMenuSeparator /><DropdownMenuItem disabled={!m.review || Boolean(m.reviewError)} onSelect={() => { if (m.review) { void navigator.clipboard.writeText(JSON.stringify(m.review,null,2)).then(() => toast.success('Conversion review copied')).catch(() => toast.error('Clipboard access is unavailable')) } }}><ListChecks size={16} />Copy conversion review</DropdownMenuItem></DropdownMenuContent></DropdownMenu></div></div></div></div>
            <ReadinessBanner job={m.job} review={m.review} dirty={m.dirty} compiling={m.compiling} view={m.view} warningCount={warningCount} readyForActions={m.readyForActions} previewAvailable={m.system?.tectonic ?? false} onBuild={m.buildPreview} onVerify={m.verifyPreview} onDownloadPdf={() => { if (m.job) downloadUrl(`/api/jobs/${m.job.id}/preview`, 'preview.pdf') }} onOpenView={m.setView} />
            <div className={`ui-review-comparison ${comparing ? 'has-original' : ''}`}>{comparing && originalItem && <OriginalTextPanel item={originalItem} paragraphs={m.review?.originalText ?? []} truncated={m.review?.originalTruncated ?? false} onClose={() => setOriginalSelection(null)} />}<div className="ui-review-editor"><Tabs value={m.view} onValueChange={value => m.setView(value as WorkspaceModel['view'])} className="ui-editor-tabs"><div className="editor-shell"><div className="editor-toolbar"><TabsList variant="line" className="ui-tabs-list" aria-label="Output view"><TabsTrigger value="source"><BracketsCurly size={16} /><span>Source</span>{m.dirty && <i className="unsaved-dot" />}</TabsTrigger><TabsTrigger value="pdf"><FileText size={16} /><span>Preview</span></TabsTrigger><TabsTrigger value="review"><ListChecks size={16} /><span>Review</span>{reviewActionCount > 0 && <b className="ui-tab-count">{reviewActionCount}</b>}</TabsTrigger></TabsList><div className="editor-view-actions">{m.completed && m.view === 'source' && <Button variant="ghost" className="editor-build-button" disabled={!m.readyForActions || m.saving || m.compiling || !m.system?.tectonic} onClick={m.buildPreview} title={`Save and build PDF (${m.commandKey} Enter)`}>{m.compiling ? 'Building…' : 'Build PDF'}</Button>}{m.completed && <Button variant="ghost" size="icon-sm" aria-label={editorFocused ? 'Exit expanded editor' : 'Expand editor'} aria-pressed={editorFocused} onClick={() => setFocusedJob(editorFocused ? null : m.job!.id)} title={editorFocused ? 'Show document settings' : 'More room to edit'}>{editorFocused ? <ArrowsInSimple size={16} /> : <ArrowsOutSimple size={16} />}</Button>}<Tooltip><TooltipTrigger asChild><Button variant="ghost" size="icon-sm" disabled={!m.readyForActions} onClick={m.copy} aria-label={m.copied ? 'Source copied' : 'Copy LaTeX source'}>{m.copied ? <Check size={16} /> : <Copy size={16} />}</Button></TooltipTrigger><TooltipContent>Copy LaTeX source</TooltipContent></Tooltip></div></div>
              {m.recovery && <DraftRecovery unavailable={m.missingDocument || Boolean(m.sourceError)} draft={m.recovery} revision={m.sourceRevision} onRestore={m.restoreDraft} onDiscard={m.discardDraft} />}{m.storageError && <p className="draft-storage-error" role="alert">{m.storageError}</p>}<TabsContent value="source" forceMount hidden={m.view !== 'source'} className="ui-tab-panel" aria-label="LaTeX source"><SourceWorkspace editorRef={m.editorRef} commandKey={m.commandKey} originalAvailable={!m.missingDocument} job={m.job} source={m.source} loading={(m.completed && m.sourceLoading) || m.converting} error={m.sourceError} onRetry={m.retrySource} readOnly={m.bibliographySaving || m.missingDocument || Boolean(m.recovery) || m.sourceLoading || Boolean(m.sourceError) || m.converting} view="source" onSourceChange={m.setSource} onBuild={m.buildPreview} compiling={m.compiling} saving={m.saving} dirty={m.dirty} previewAvailable={m.system?.tectonic ?? false} /></TabsContent>
              <TabsContent value="pdf" className="ui-tab-panel" aria-label="PDF preview"><SourceWorkspace editorRef={undefined} commandKey={m.commandKey} originalAvailable={!m.missingDocument} job={m.job} source={m.source} loading={(m.completed && m.sourceLoading) || m.converting} error={m.sourceError} onRetry={m.retrySource} readOnly={m.bibliographySaving || m.missingDocument || Boolean(m.recovery) || m.sourceLoading || Boolean(m.sourceError) || m.converting} view="pdf" onSourceChange={m.setSource} onBuild={m.buildPreview} compiling={m.compiling} saving={m.saving} dirty={m.dirty} previewAvailable={m.system?.tectonic ?? false} /></TabsContent>
              <TabsContent value="review" className="ui-tab-panel" aria-label="Conversion review"><Suspense fallback={<div className="review-empty" role="status">Loading conversion review…</div>}><ConversionReview review={m.review} job={m.job} dirty={m.dirty} canDecide={canReview} saving={m.reviewSaving} error={m.reviewError} onRetry={m.retryReview} onDecide={m.decideFinding} onCompare={compareOriginal} onNavigate={m.navigateSource} onCaptionRepairs={() => { toast.dismiss(); setCaptionRepairsOpen(true) }} /></Suspense></TabsContent>
              <div className="editor-statusbar"><span><i className={`status-dot ${m.completed ? 'ready' : ''}`} />{m.sourceError ? 'Source unavailable' : m.completed && m.sourceLoading ? 'Loading source' : m.completed ? m.dirty ? 'Unsaved changes' : `main.tex · ${m.source.split('\n').length} lines` : m.converting ? 'Preparing your document' : 'Your source will appear here'}</span><span>UTF-8 <span className="statusbar-separator">/</span> LaTeX</span></div></div></Tabs></div></div>
            {m.job && m.completed && <CompilerDiagnosticsPanel key={m.job.id} job={m.job} dirty={m.dirty} canNavigate={m.readyForActions && !m.saving && !m.compiling} onNavigate={m.navigateSource} />}
            {m.job && m.completed && <details className="ui-bibliography" onToggle={event => setBibliographyOpen(event.currentTarget.open)}><summary>Bibliography</summary>{bibliographyOpen && <Suspense fallback={<p role="status">Loading reference library…</p>}><BibliographyPanel key={m.job.id} job={m.job} dirty={m.dirty} disabled={!m.readyForActions || m.saving || m.compiling} onImport={m.importBibliography} onSetup={m.setupBibliography} onInsert={m.insertCitation} /></Suspense>}</details>}
          </section>
        </div>
        </div>
        <footer className="ui-footer"><span><Info size={15} />Conversion runs locally. Documents are stored only on this machine.</span><button onClick={() => setGuideOpen(true)}>What converts well <ArrowUpRight size={13} /></button></footer>
      </div>
    </main>
    {libraryOpen && <Suspense fallback={<p role="status">Loading document library…</p>}><DocumentLibrary open={libraryOpen} onOpenChange={setLibraryOpen} onSelect={m.openJob} onRenamed={m.renamed} onDeleted={m.deleted} disabled={m.switchingDisabled} /></Suspense>}
    {versionsOpen && <Suspense fallback={<p role="status">Loading saved versions…</p>}><VersionHistory open={versionsOpen} onOpenChange={setVersionsOpen} job={m.job} disabled={!m.versionsAvailable || m.compiling || m.saving} onRestore={m.restoreVersion} /></Suspense>}
    <Dialog open={guideOpen} onOpenChange={setGuideOpen}><DialogContent className="ui-guide-dialog"><DialogHeader><DialogTitle>Conversion guide</DialogTitle><DialogDescription>What converts cleanly from Word to LaTeX, and what to check afterwards.</DialogDescription></DialogHeader><div className="ui-guide-content"><div><CheckCircle size={20} /><section><h3>Converts cleanly</h3><p>Use Word heading styles, native equations, lists, footnotes, and PNG or JPEG figures. Choose Article or Report to set the LaTeX document class.</p></section></div><div><ListChecks size={20} /><section><h3>Check after conversion</h3><p>Review merged tables, multilingual fonts, references, text boxes, and floating figures. Word headers, footers, comments, charts, and embedded objects may need manual work. Tracked changes are accepted.</p></section></div><div><FolderSimple size={20} /><section><h3>One ZIP export</h3><p>Your saved source, extracted images, README, and conversion review are bundled together. You can edit the source here or import the ZIP into Overleaf.</p></section></div><div><FileText size={20} /><section><h3>PDF preview is a separate step</h3><p>Build the preview when you are ready. First use may download TeX packages. A successful build checks syntax; it does not guarantee that every Word element survived.</p></section></div></div></DialogContent></Dialog>
    {captionRepairsOpen && <Suspense fallback={null}><CaptionRepairsDialog open={captionRepairsOpen} onOpenChange={setCaptionRepairsOpen} job={m.job} disabled={!m.readyForActions || m.dirty || m.saving || m.compiling} onApply={m.applyCaptionRepairs} /></Suspense>}
    {setupOpen && <Suspense fallback={<p role="status">Loading setup diagnostics…</p>}><SetupDiagnostics onClose={() => setSetupOpen(false)} onStatus={m.setSystem} /></Suspense>}
    <Dialog open={historyOpen} onOpenChange={setHistoryOpen}><DialogContent className="ui-history-dialog"><DialogHeader><DialogTitle>Recent documents</DialogTitle><DialogDescription>Conversions stored on this machine.</DialogDescription></DialogHeader><div className="ui-mobile-history-list">{m.history.length ? m.history.map(job => <Button variant="ghost" key={job.id} disabled={m.switchingDisabled} onClick={() => { setHistoryOpen(false); m.openJob(job) }}><FileText size={17} /><span>{jobTitle(job)}</span>{job.status === 'COMPLETED' && <CheckCircle size={15} />}</Button>) : <p>Convert your first document to start your history.</p>}</div></DialogContent></Dialog>
  </div>
}
