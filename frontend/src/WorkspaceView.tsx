import { useRef, useState } from 'react'
import { ArrowDown, ArrowRight, ArrowUpRight, BracketsCurly, Check, CheckCircle, Clock,
  Copy, DotsThree, FileDoc, FileText, FolderSimple, Info, ListChecks, Plus,
  UploadSimple, WarningCircle, X } from '@phosphor-icons/react'
import { toast } from 'sonner'
import type { ConversionReview as Review, Job, SystemStatus } from '@/api'
import { downloadUrl, formatBytes } from '@/api'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { Progress } from '@/components/ui/progress'
import { SourceWorkspace } from '@/SourceWorkspace'
import { ConversionReview } from '@/components/ConversionReview'

export interface WorkspaceModel {
  system: SystemStatus | null; history: Job[]; job: Job | null; file: File | null; template: string
  source: string; sourceLoading: boolean; error: string; saving: boolean; dragging: boolean
  copied: boolean; view: 'source' | 'pdf' | 'review'; dirty: boolean; converting: boolean; compiling: boolean
  switchingDisabled: boolean; completed: boolean; readyForActions: boolean; review: Review | null
  selectFile: (file: File | undefined) => void; setDragging: (value: boolean) => void
  setTemplate: (value: string) => void; setView: (value: 'source' | 'pdf' | 'review') => void
  setSource: (value: string) => void; setError: (value: string) => void
  reset: () => void; openJob: (job: Job) => void; trySample: () => void; startConversion: () => void
  save: () => void; copy: () => void; download: () => void; downloadTex: () => void; buildPreview: () => void
}

export function WorkspaceView({ model: m }: { model: WorkspaceModel }) {
  const fileInput = useRef<HTMLInputElement>(null)
  const [guideOpen, setGuideOpen] = useState(false)
  const [historyOpen, setHistoryOpen] = useState(false)
  const warningCount = (m.review?.findings.filter(item => item.severity === 'warning').length ?? 0) + (m.review?.compilerWarnings.length ?? 0)
  const activeFilename = m.file?.name ?? m.job?.filename
  return <div className="ui-workspace">
    <a className="skip-link" href="#workspace-main">Skip to conversion workspace</a>
    <aside className="ui-sidebar">
      <button onClick={m.reset} className="ui-brand" aria-label="doc2TeX home"><span className="ui-brand-icon"><BracketsCurly size={22} /></span>doc<span>2</span>TeX<span className="ui-brand-dot">.</span></button>
      <Badge variant="outline" className="ui-edition">A workspace for your words</Badge>
      <Button className="ui-new-button" onClick={m.reset} disabled={m.switchingDisabled}><Plus size={16} />New conversion</Button>
      <div className="ui-recent-label"><span>Recent documents</span><span>{m.history.length.toString().padStart(2,'0')}</span></div>
      <nav className="ui-recent-list" aria-label="Recent conversions">
        {m.history.length ? m.history.slice(0,10).map(job => <button key={job.id} disabled={m.switchingDisabled} onClick={() => m.openJob(job)} className={`ui-recent-file ${m.job?.id === job.id ? 'active' : ''}`}><FileText size={18} /><span><strong>{job.filename}</strong><small>{job.status === 'COMPLETED' ? 'Ready to edit' : job.status === 'FAILED' ? 'Needs attention' : 'Converting…'}</small></span>{job.status === 'COMPLETED' && <span className="ui-recent-dot" />}</button>) : <div className="ui-recent-empty"><FolderSimple size={25} weight="light" /><p>Your next draft starts here.<br />Conversions will appear as you go.</p></div>}
      </nav>
      <div className="ui-sidebar-bottom"><div className="ui-local-note"><span className="ui-local-dot" /><div><strong>On your machine</strong><p>Your documents stay local.</p></div></div><Button variant="ghost" className="ui-guide-button" onClick={() => setGuideOpen(true)}><Info size={16} />Conversion guide<ArrowUpRight size={14} /></Button><span className="ui-version">doc2TeX <span>v0.2 / local edition</span></span></div>
    </aside>
    <main className="ui-main" id="workspace-main">
      <header className="ui-topbar"><div><span>Workspace</span><span className="ui-breadcrumb-divider">/</span><strong>{m.job ? m.job.filename : 'New conversion'}</strong></div><div><Button className="ui-mobile-history" variant="ghost" size="icon-sm" aria-label="Recent documents" onClick={() => setHistoryOpen(true)}><Clock size={17} /></Button><Tooltip><TooltipTrigger asChild><Badge variant="outline" className="ui-system-status"><span className={m.system?.pandoc ? 'available' : ''} />{m.system?.pandoc ? 'Ready to convert' : m.system ? 'Setup needed' : 'Connecting'}</Badge></TooltipTrigger><TooltipContent>{m.system?.pandoc ? 'The document converter is available on this machine.' : 'Checking the local conversion service.'}</TooltipContent></Tooltip></div></header>
      <div className="ui-content">
        <section className="ui-intro"><div><span className="ui-eyebrow"><span />FROM FIRST DRAFT TO FINAL FORM</span><h1>Your words.<br /><span>Ready for their next chapter.</span></h1><p>Bring your Word document. Leave with LaTeX you can make your own.</p></div><div className="ui-format-illustration" aria-hidden="true"><div><FileDoc size={25} weight="light" /><span>DOCX</span></div><span className="ui-route-line" /><ArrowRight size={19} /><div><BracketsCurly size={25} weight="light" /><span>LaTeX</span></div></div></section>
        {m.system && !m.system.pandoc && <div className="ui-notice"><Info size={19} /><p><strong>Install the document converter.</strong> Run <code>scripts/setup-tools.ps1</code> and restart the app.</p></div>}
        {m.error && <div className="ui-error" role="alert"><WarningCircle size={20} /><p>{m.error}</p><Button variant="ghost" size="icon-sm" aria-label="Dismiss error" onClick={() => m.setError('')}><X size={16} /></Button></div>}
        <div className="ui-workbench">
          <section className="ui-input-panel" aria-label="Document settings"><div className="ui-panel-heading"><span className="ui-step">01</span><h2>The original</h2><span>Word document</span></div>
            <div className={`ui-dropzone ${m.dragging ? 'is-dragging' : ''} ${activeFilename ? 'is-selected' : ''}`} onDragOver={event => { event.preventDefault(); if (!m.converting) m.setDragging(true) }} onDragLeave={() => m.setDragging(false)} onDrop={event => { event.preventDefault(); m.setDragging(false); if (!m.converting) m.selectFile(event.dataTransfer.files[0]) }}>
              <input ref={fileInput} type="file" className="sr-only" aria-label="Upload Word document" accept=".docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document" disabled={m.converting} onChange={event => { m.selectFile(event.target.files?.[0]); event.target.value = '' }} />
              <div className="ui-upload-icon"><FileDoc size={34} weight="light" /><span>.docx</span></div>
              <h3>{activeFilename || 'A fresh page starts here.'}</h3><p>{m.file ? `${formatBytes(m.file.size)} · Ready to convert` : m.job ? `${formatBytes(m.job.size)} · Saved conversion` : 'Drop your document, or choose one below.'}</p>
              <Button variant="outline" className="ui-choose-button" disabled={m.converting} onClick={() => fileInput.current?.click()}><UploadSimple size={16} />{activeFilename ? 'Choose another document' : 'Choose a document'}</Button><span className="ui-upload-limit">.docx only <span>·</span> up to 20 MB</span>
            </div>
            <button className="ui-sample-link" onClick={m.trySample} disabled={m.converting || m.saving || m.system?.pandoc === false}>Explore with a sample document<ArrowUpRight size={13} /></button>
            <div className="ui-template-label"><h3>Choose a starting point</h3><span>LaTeX template</span></div>
            <RadioGroup value={m.template} onValueChange={m.setTemplate} disabled={m.converting} className="ui-template-list" aria-label="LaTeX template">{[{id:'article',title:'Article',detail:'Papers, essays, and focused ideas',icon:FileText},{id:'report',title:'Report',detail:'Chapters and longer documents',icon:FolderSimple}].map(item => <label key={item.id} htmlFor={`template-${item.id}`} className={`ui-template ${m.template === item.id ? 'selected' : ''}`}><item.icon size={22} weight="light" /><span><strong>{item.title}</strong><small>{item.detail}</small></span><RadioGroupItem id={`template-${item.id}`} value={item.id} aria-label={item.title} /></label>)}</RadioGroup>
            <Button className="ui-convert-button" disabled={!m.file || m.converting || m.saving || m.system?.pandoc === false} onClick={m.startConversion}>{m.converting ? 'Converting your document…' : 'Convert to LaTeX'}<ArrowRight size={18} /></Button>
            <p className="ui-convert-note"><CheckCircle size={14} />Editable source. Images included.</p>
            {m.job && <div className={`ui-job-status ${m.job.status === 'FAILED' ? 'failed' : ''}`} role="status"><div>{m.completed ? <CheckCircle size={19} weight="fill" /> : m.job.status === 'FAILED' ? <WarningCircle size={19} /> : <Clock size={19} />}<strong>{m.completed ? 'Your next draft is ready.' : m.job.status === 'FAILED' ? 'This document needs attention.' : 'Making room for your words…'}</strong></div><p>{m.job.message}</p>{m.converting && <Progress className="ui-job-progress" />}</div>}
            <div className="ui-input-note"><Info size={16} /><p>Best with Word heading styles and native equations. Complex layouts may need a final edit.</p></div>
          </section>
          <section className="ui-output-panel" aria-label="LaTeX output"><div className="ui-panel-heading"><span className="ui-step">02</span><h2>The next draft</h2><Badge variant="outline" className="ui-output-state">{m.completed ? m.dirty ? 'Unsaved changes' : 'Ready to edit' : 'Your output'}</Badge></div>
            <Tabs value={m.view} onValueChange={value => m.setView(value as WorkspaceModel['view'])} className="ui-editor-tabs"><div className="editor-shell"><div className="editor-toolbar"><TabsList variant="line" className="ui-tabs-list" aria-label="Output view"><TabsTrigger value="source"><BracketsCurly size={16} /><span>Source</span>{m.dirty && <i className="unsaved-dot" />}</TabsTrigger><TabsTrigger value="pdf"><FileText size={16} /><span>Preview</span></TabsTrigger><TabsTrigger value="review"><ListChecks size={16} /><span>Review</span>{warningCount > 0 && <b className="ui-tab-count">{warningCount}</b>}</TabsTrigger></TabsList><Tooltip><TooltipTrigger asChild><Button variant="ghost" size="icon-sm" disabled={!m.readyForActions} onClick={m.copy} aria-label={m.copied ? 'Source copied' : 'Copy LaTeX source'}>{m.copied ? <Check size={16} /> : <Copy size={16} />}</Button></TooltipTrigger><TooltipContent>Copy LaTeX source</TooltipContent></Tooltip></div>
              <TabsContent value={m.view} className="ui-tab-panel" aria-label={m.view === 'review' ? 'Conversion review' : m.view === 'pdf' ? 'PDF preview' : 'LaTeX source'}>{m.view === 'review' ? <ConversionReview review={m.review} job={m.job} /> : <SourceWorkspace job={m.job} source={m.source} loading={(m.completed && m.sourceLoading) || m.converting} view={m.view} onSourceChange={m.setSource} onBuild={m.buildPreview} compiling={m.compiling} saving={m.saving} dirty={m.dirty} previewAvailable={m.system?.tectonic ?? false} />}</TabsContent>
              <div className="editor-statusbar"><span><i className={`status-dot ${m.completed ? 'ready' : ''}`} />{m.completed && m.sourceLoading ? 'Loading source' : m.completed ? m.dirty ? 'Unsaved changes' : `main.tex · ${m.source.split('\n').length} lines` : m.converting ? 'Preparing your document' : 'Your source will appear here'}</span><span>UTF-8 <span className="statusbar-separator">/</span> LaTeX</span></div></div></Tabs>
            <div className="ui-output-actions"><span><FolderSimple size={16} />Source + images. One tidy ZIP.</span><div><Button variant="outline" onClick={m.save} disabled={!m.readyForActions || !m.dirty || m.saving || m.compiling}>{m.saving ? 'Saving…' : 'Save changes'}</Button><div className="ui-download-group"><Button className="ui-download-main" disabled={!m.readyForActions || m.saving || (m.compiling && m.dirty)} onClick={m.download}><ArrowDown size={16} />Download ZIP</Button><DropdownMenu><DropdownMenuTrigger asChild><Button className="ui-download-options" aria-label="More download options" disabled={!m.readyForActions}><DotsThree size={19} weight="bold" /></Button></DropdownMenuTrigger><DropdownMenuContent align="end"><DropdownMenuLabel>Take your work with you</DropdownMenuLabel><DropdownMenuSeparator /><DropdownMenuItem onSelect={m.downloadTex}><BracketsCurly size={16} />Current LaTeX source</DropdownMenuItem><DropdownMenuItem disabled={m.job?.previewStatus !== 'READY' || m.dirty} onSelect={() => { if (m.job) downloadUrl(`/api/jobs/${m.job.id}/preview`, 'preview.pdf') }}><FileText size={16} />Saved PDF</DropdownMenuItem><DropdownMenuItem onSelect={() => { if (m.review) { void navigator.clipboard.writeText(JSON.stringify(m.review,null,2)).then(() => toast.success('Conversion review copied')).catch(() => toast.error('Clipboard access is unavailable')) } }}><ListChecks size={16} />Copy conversion review</DropdownMenuItem></DropdownMenuContent></DropdownMenu></div></div></div>
            {m.completed && warningCount > 0 && m.view !== 'review' && <button className="ui-review-prompt" onClick={() => m.setView('review')}><WarningCircle size={16} /><span>{warningCount} {warningCount === 1 ? 'detail' : 'details'} worth a closer look.</span><span>View review <ArrowRight size={13} /></span></button>}
          </section>
        </div>
        <footer className="ui-footer"><span><BracketsCurly size={16} />A little less formatting. A little more thinking.</span><button onClick={() => setGuideOpen(true)}>What converts well <ArrowUpRight size={13} /></button></footer>
      </div>
    </main>
    <Dialog open={guideOpen} onOpenChange={setGuideOpen}><DialogContent className="ui-guide-dialog"><DialogHeader><DialogTitle>A good document makes a good start.</DialogTitle><DialogDescription>What to expect when moving from Word to LaTeX.</DialogDescription></DialogHeader><div className="ui-guide-content"><div><CheckCircle size={20} /><section><h3>Structure travels well</h3><p>Use Word heading styles, native equations, lists, footnotes, and PNG or JPEG figures. Choose Article or Report to set the LaTeX document class.</p></section></div><div><ListChecks size={20} /><section><h3>Leave a little room for review</h3><p>Review merged tables, multilingual fonts, references, text boxes, and floating figures. Word headers, footers, comments, charts, and embedded objects may need manual work. Tracked changes are accepted.</p></section></div><div><FolderSimple size={20} /><section><h3>Everything you need in one ZIP</h3><p>Your saved source, extracted images, README, and conversion review are bundled together. You can edit the source here or import the ZIP into Overleaf.</p></section></div><div><FileText size={20} /><section><h3>PDF is a separate step</h3><p>Build the preview when you are ready. First use may download TeX packages. A successful build checks syntax; it does not guarantee that every Word element survived.</p></section></div></div></DialogContent></Dialog>
    <Dialog open={historyOpen} onOpenChange={setHistoryOpen}><DialogContent className="ui-history-dialog"><DialogHeader><DialogTitle>Pick up where you left off.</DialogTitle><DialogDescription>Your recent documents, saved on this machine.</DialogDescription></DialogHeader><div className="ui-mobile-history-list">{m.history.length ? m.history.map(job => <Button variant="ghost" key={job.id} disabled={m.switchingDisabled} onClick={() => { setHistoryOpen(false); m.openJob(job) }}><FileText size={17} /><span>{job.filename}</span>{job.status === 'COMPLETED' && <CheckCircle size={15} />}</Button>) : <p>Convert your first document to start your history.</p>}</div></DialogContent></Dialog>
  </div>
}
