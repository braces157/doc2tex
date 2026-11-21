import { useEffect, useMemo, useRef, useState } from 'react'
import { FilePdf, FileText, Images, Scissors, ShieldCheck, Sparkle, UploadSimple, WarningCircle, X } from '@phosphor-icons/react'
import { toast } from 'sonner'
import type { Job } from './api'
import { jobTitle } from './api'
import { PdfExtractTab } from './components/PdfExtractTab'
import { PdfInspectTab } from './components/PdfInspectTab'
import { PdfLoadedStrip } from './components/PdfLoadedStrip'
import { PdfOrganizeTab } from './components/PdfOrganizeTab'
import { PdfToSourceTab } from './components/PdfToSourceTab'
import { Button } from './components/ui/button'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel,
  DropdownMenuSeparator, DropdownMenuTrigger,
} from './components/ui/dropdown-menu'
import { Tabs, TabsContent, TabsList, TabsTrigger } from './components/ui/tabs'
import {
  assemblePdf, downloadBlob, extractPdfImages, extractPdfMarkdown, extractPdfText,
  inspectPdf, parsePageRange,
  type ExtractOutput, type LoadedPdf, type OrganizerPage, type PdfInspection,
} from './pdfApi'
import { releasePdfProxy } from './pdfWorker'

let nextSequence = 0
const nextId = (prefix: string) => `${prefix}-${++nextSequence}`

const MAX_FILE_BYTES = 20 * 1024 * 1024
const MAX_TOTAL_BYTES = 50 * 1024 * 1024
const MAX_FILES = 20
const MAX_OUTPUT_PAGES = 500

function makePages(doc: LoadedPdf, inspection: PdfInspection): OrganizerPage[] {
  return inspection.pages.map(page => ({
    key: nextId(`${doc.uid}-p${page.number}`),
    docUid: doc.uid,
    docName: inspection.filename,
    pageNumber: page.number,
    rotation: 0,
    widthMm: page.widthMm,
    heightMm: page.heightMm,
  }))
}

export default function PdfStudio({ history }: { history: Job[] }) {
  const fileInputRef = useRef<HTMLInputElement>(null)
  const docsRef = useRef<LoadedPdf[]>([])
  const inspectionTokensRef = useRef<Map<string, number>>(new Map())
  const inspectionSeqRef = useRef(0)
  const [docs, setDocs] = useState<LoadedPdf[]>([])
  const [pages, setPages] = useState<OrganizerPage[]>([])
  const [tab, setTab] = useState<'organize' | 'inspect' | 'extract' | 'convert'>('organize')
  const [selectedDocUid, setSelectedDocUid] = useState<string>('')
  const [dragging, setDragging] = useState(false)
  const [error, setError] = useState('')
  const [busyMessage, setBusyMessage] = useState('')
  const [rangeExpr, setRangeExpr] = useState('')
  const [scrubMetadata, setScrubMetadata] = useState(true)
  const [stripAnnotations, setStripAnnotations] = useState(false)
  const [pageNumbers, setPageNumbers] = useState(false)
  const [watermarkText, setWatermarkText] = useState('')
  const [watermarkOpacity, setWatermarkOpacity] = useState(0.18)
  const [outputName, setOutputName] = useState('assembled.pdf')
  const [passwordDrafts, setPasswordDrafts] = useState<Record<string, string>>({})
  const [extractResult, setExtractResult] = useState<ExtractOutput | null>(null)
  const [copiedExtract, setCopiedExtract] = useState(false)

  useEffect(() => {
    docsRef.current = docs
  }, [docs])

  useEffect(() => {
    return () => {
      for (const doc of docsRef.current) releasePdfProxy(doc.file)
    }
  }, [])

  const readyWorkspaceJobs = useMemo(
    () => history.filter(item => item.status === 'COMPLETED' && item.previewStatus === 'READY'),
    [history],
  )
  const activeDoc = useMemo(
    () => docs.find(doc => doc.uid === selectedDocUid) ?? docs[0] ?? null,
    [docs, selectedDocUid],
  )
  const totalLoadedBytes = useMemo(
    () => docs.reduce((sum, item) => sum + item.file.size, 0),
    [docs],
  )

  const inspectEntry = async (entry: LoadedPdf, password?: string) => {
    const token = ++inspectionSeqRef.current
    inspectionTokensRef.current.set(entry.uid, token)
    setDocs(prev => prev.map(item =>
      item.uid === entry.uid ? { ...item, loading: true, error: '', needsPassword: false } : item))
    try {
      const inspection = await inspectPdf(entry.file, password)
      if (inspectionTokensRef.current.get(entry.uid) !== token) return
      setDocs(prev => prev.map(item =>
        item.uid === entry.uid
          ? { ...item, loading: false, password, inspection, needsPassword: false, error: '' }
          : item))
      setPages(prev => [
        ...prev.filter(page => page.docUid !== entry.uid),
        ...makePages(entry, inspection),
      ])
    } catch (failure) {
      if (inspectionTokensRef.current.get(entry.uid) !== token) return
      const message = failure instanceof Error ? failure.message : 'Could not inspect this PDF.'
      const needsPassword = message.toLowerCase().includes('password')
      setDocs(prev => prev.map(item =>
        item.uid === entry.uid ? { ...item, loading: false, needsPassword, error: message } : item))
    }
  }

  const addFiles = async (fileList: FileList | File[]) => {
    const incoming = Array.from(fileList)
    if (incoming.length === 0) return
    setError('')

    const accepted: LoadedPdf[] = []
    let runningBytes = totalLoadedBytes
    for (const file of incoming) {
      if (docs.length + accepted.length >= MAX_FILES) {
        setError(`You can load up to ${MAX_FILES} PDF files at once.`)
        break
      }
      if (!file.name.toLowerCase().endsWith('.pdf')) {
        setError(`"${file.name}" is not a .pdf file. Choose PDF documents only.`)
        continue
      }
      if (file.size === 0 || file.size > MAX_FILE_BYTES) {
        setError(`"${file.name}" must be between 1 byte and 20 MB.`)
        continue
      }
      if (runningBytes + file.size > MAX_TOTAL_BYTES) {
        setError(`Combined PDF uploads cannot exceed 50 MB. Remove a file before adding "${file.name}".`)
        break
      }
      runningBytes += file.size
      accepted.push({ uid: nextId('pdf'), file, inspection: null, loading: true, needsPassword: false, error: '' })
    }

    if (accepted.length === 0) return
    setDocs(prev => [...prev, ...accepted])
    if (!selectedDocUid) setSelectedDocUid(accepted[0].uid)
    if (docs.length === 0 && accepted.length === 1) {
      const base = accepted[0].file.name.replace(/\.pdf$/i, '') || 'document'
      setOutputName(`${base}-edited.pdf`)
    } else {
      setOutputName('assembled.pdf')
    }
    for (const entry of accepted) await inspectEntry(entry)
  }

  const importWorkspaceJob = async (job: Job) => {
    setError('')
    setBusyMessage(`Importing ${jobTitle(job)} preview PDF…`)
    try {
      const response = await fetch(`/api/jobs/${job.id}/preview?r=${job.revision}`)
      if (!response.ok) throw new Error('Could not fetch the compiled workspace PDF.')
      const blob = await response.blob()
      const base = jobTitle(job).replace(/\.docx$/i, '') || 'preview'
      await addFiles([new File([blob], `${base}.pdf`, { type: 'application/pdf' })])
      toast.success(`Imported ${base}.pdf into PDF Studio`)
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not import the workspace PDF.')
    } finally {
      setBusyMessage('')
    }
  }

  const removeDoc = (uid: string) => {
    inspectionTokensRef.current.delete(uid)
    const target = docs.find(item => item.uid === uid)
    if (target) releasePdfProxy(target.file)
    setDocs(prev => prev.filter(item => item.uid !== uid))
    setPages(prev => prev.filter(page => page.docUid !== uid))
    if (selectedDocUid === uid) {
      setSelectedDocUid(docs.filter(item => item.uid !== uid)[0]?.uid ?? '')
    }
    if (extractResult?.docUid === uid) setExtractResult(null)
  }

  const clearAll = () => {
    inspectionTokensRef.current.clear()
    for (const doc of docs) releasePdfProxy(doc.file)
    setDocs([])
    setPages([])
    setSelectedDocUid('')
    setExtractResult(null)
    setError('')
    setRangeExpr('')
  }

  const movePage = (index: number, delta: -1 | 1) => {
    const targetIndex = index + delta
    if (targetIndex < 0 || targetIndex >= pages.length) return
    setPages(prev => {
      const next = [...prev]
      const [moved] = next.splice(index, 1)
      next.splice(targetIndex, 0, moved)
      return next
    })
  }

  const applyRangeFilter = () => {
    if (pages.length === 0) return
    setError('')
    try {
      const chosen = parsePageRange(rangeExpr, pages.length)
      if (chosen.length > MAX_OUTPUT_PAGES) {
        setError(`Selected pages cannot exceed ${MAX_OUTPUT_PAGES}.`)
        return
      }
      setPages(prev => chosen.map(oneBased => ({ ...prev[oneBased - 1], key: nextId(prev[oneBased - 1].docUid) })))
      toast.success(`Kept ${chosen.length} page${chosen.length === 1 ? '' : 's'} from range`)
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Invalid page range.')
    }
  }

  const resetPagesOrder = () => {
    const rebuilt: OrganizerPage[] = []
    for (const doc of docs) {
      if (doc.inspection) rebuilt.push(...makePages(doc, doc.inspection))
    }
    setPages(rebuilt)
    setRangeExpr('')
  }

  const runAssemble = async (pageSpecsOverride?: { files: File[]; passwords?: string[]; specs: { fileIndex: number; pageNumber: number; rotation: number }[]; name: string }) => {
    setError('')
    if (pages.length === 0 && !pageSpecsOverride) {
      setError('Add at least one PDF page before exporting.')
      return
    }
    const readyDocs = docs.filter(item => item.inspection)
    if (readyDocs.length === 0) {
      setError('Wait for PDF inspection to finish before exporting.')
      return
    }
    const docIndexByUid = new Map<string, number>()
    readyDocs.forEach((doc, index) => docIndexByUid.set(doc.uid, index))

    const filesToSend = pageSpecsOverride ? pageSpecsOverride.files : readyDocs.map(item => item.file)
    const passwordsToSend = pageSpecsOverride
      ? (pageSpecsOverride.passwords ?? [])
      : readyDocs.map(item => item.password ?? '')
    let specs: { fileIndex: number; pageNumber: number; rotation: number }[]
    if (pageSpecsOverride) {
      specs = pageSpecsOverride.specs
    } else {
      const missingDoc = pages.some(page => !docIndexByUid.has(page.docUid))
      if (missingDoc) {
        setError('One or more queued pages belong to a removed or uninspected PDF. Remove stale pages or reset the order before exporting.')
        return
      }
      specs = pages.map(page => ({
        fileIndex: docIndexByUid.get(page.docUid)!,
        pageNumber: page.pageNumber,
        rotation: page.rotation,
      }))
    }
    if (specs.length === 0 || specs.length > MAX_OUTPUT_PAGES) {
      setError(`Choose between 1 and ${MAX_OUTPUT_PAGES} pages to export.`)
      return
    }

    setBusyMessage('Building assembled PDF…')
    try {
      const download = await assemblePdf(filesToSend, {
        pages: specs,
        scrubMetadata,
        stripAnnotations,
        watermarkText,
        watermarkOpacity,
        pageNumbers,
        outputName: pageSpecsOverride ? pageSpecsOverride.name : outputName,
        passwords: passwordsToSend,
      })
      downloadBlob(download.blob, download.filename)
      toast.success(`Downloaded ${download.filename}`)
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not assemble the PDF.')
    } finally {
      setBusyMessage('')
    }
  }

  const runTextOrMarkdown = async (kind: 'text' | 'markdown') => {
    if (!activeDoc) return
    setError('')
    setBusyMessage(kind === 'text' ? 'Extracting plain text…' : 'Extracting Markdown…')
    try {
      if (kind === 'text') {
        const result = await extractPdfText(activeDoc.file, activeDoc.password)
        const formatted = result.pages
          .map(page => `--- Page ${page.pageNumber} ---\n${page.text || '[No selectable text]'}`)
          .join('\n\n')
        setExtractResult({
          docUid: activeDoc.uid,
          kind: 'text',
          content: formatted,
          totalChars: result.totalChars,
          pageCount: result.pageCount,
          truncated: result.truncated,
        })
      } else {
        const result = await extractPdfMarkdown(activeDoc.file, activeDoc.password)
        setExtractResult({
          docUid: activeDoc.uid,
          kind: 'markdown',
          content: result.markdown,
          totalChars: result.totalChars,
          pageCount: result.pageCount,
          truncated: result.truncated,
        })
      }
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not extract PDF text.')
    } finally {
      setBusyMessage('')
    }
  }

  const runImageExtract = async () => {
    if (!activeDoc) return
    setError('')
    setBusyMessage('Extracting embedded images…')
    try {
      const result = await extractPdfImages(activeDoc.file, activeDoc.password)
      downloadBlob(result.blob, result.filename)
      toast.success(`Downloaded ${result.filename}`)
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not extract embedded images.')
    } finally {
      setBusyMessage('')
    }
  }

  const copyExtractContent = async () => {
    if (!extractResult) return
    try {
      await navigator.clipboard.writeText(extractResult.content)
      setCopiedExtract(true)
      toast.success('Extracted content copied')
      setTimeout(() => setCopiedExtract(false), 1800)
    } catch {
      setError('Clipboard access is unavailable. Download the extracted file instead.')
    }
  }

  const hasDocs = docs.length > 0
  const busy = Boolean(busyMessage)

  return (
    <section className="pdf-studio" aria-label="PDF Studio">
      <input
        ref={fileInputRef}
        type="file"
        multiple
        accept=".pdf,application/pdf"
        className="sr-only"
        aria-label="Upload PDF files"
        onChange={event => {
          if (event.target.files) void addFiles(event.target.files)
          event.target.value = ''
        }}
      />

      <header className="pdf-studio-header">
        <div>
          <h1>PDF Studio</h1>
          <p>Merge, split, reorder, rotate, scrub metadata, watermark, and extract text or images locally.</p>
        </div>
        <div className="pdf-studio-top-actions">
          {readyWorkspaceJobs.length > 0 && (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="outline" disabled={busy}>
                  <FileText size={15} />
                  Import workspace PDF
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                <DropdownMenuLabel>Compiled conversions</DropdownMenuLabel>
                <DropdownMenuSeparator />
                {readyWorkspaceJobs.slice(0, 10).map(item => (
                  <DropdownMenuItem key={item.id} onSelect={() => void importWorkspaceJob(item)}>
                    <FilePdf size={15} />
                    <span>{jobTitle(item)}</span>
                  </DropdownMenuItem>
                ))}
              </DropdownMenuContent>
            </DropdownMenu>
          )}
          <Button variant="outline" disabled={busy} onClick={() => fileInputRef.current?.click()}>
            <UploadSimple size={15} />
            Add PDF files
          </Button>
          {hasDocs && (
            <Button variant="ghost" disabled={busy} onClick={clearAll}>
              Clear all
            </Button>
          )}
        </div>
      </header>

      {error && (
        <div className="ui-error" role="alert">
          <WarningCircle size={19} />
          <p>{error}</p>
          <Button variant="ghost" size="icon-sm" aria-label="Dismiss PDF Studio error" onClick={() => setError('')}>
            <X size={16} />
          </Button>
        </div>
      )}

      {busyMessage && (
        <div className="pdf-studio-status" role="status" aria-busy="true">
          <span>{busyMessage}</span>
        </div>
      )}

      {!hasDocs ? (
        <div
          className={`pdf-studio-dropzone ${dragging ? 'is-dragging' : ''}`}
          onDragOver={event => { event.preventDefault(); setDragging(true) }}
          onDragLeave={() => setDragging(false)}
          onDrop={event => {
            event.preventDefault()
            setDragging(false)
            if (event.dataTransfer.files.length) void addFiles(event.dataTransfer.files)
          }}
        >
          <div className="ui-upload-icon">
            <FilePdf size={26} />
            <span>PDF</span>
          </div>
          <h2>Drop PDF files here</h2>
          <p>Inspect structure, reorder or rotate pages, merge multiple files, scrub hidden metadata, or extract assets.</p>
          <Button variant="outline" onClick={() => fileInputRef.current?.click()}>
            <UploadSimple size={15} />
            Choose PDF files
          </Button>
          <span className="ui-upload-limit">.pdf up to 20 MB each <span>·</span> 50 MB total <span>·</span> up to 500 pages</span>
        </div>
      ) : (
        <div className="pdf-studio-loaded">
          <PdfLoadedStrip
            docs={docs}
            totalLoadedBytes={totalLoadedBytes}
            busy={busy}
            passwordDrafts={passwordDrafts}
            setPasswordDrafts={setPasswordDrafts}
            onUnlock={(doc, pw) => void inspectEntry(doc, pw)}
            onRemove={removeDoc}
          />

          <Tabs value={tab} onValueChange={value => setTab(value as typeof tab)} className="pdf-studio-tabs">
            <div className="pdf-tabs-bar">
              <TabsList variant="line" className="ui-tabs-list" aria-label="PDF Studio tools">
                <TabsTrigger value="organize"><Scissors size={16} /><span>Organize &amp; Merge</span></TabsTrigger>
                <TabsTrigger value="inspect"><ShieldCheck size={16} /><span>Inspect &amp; Privacy</span></TabsTrigger>
                <TabsTrigger value="extract"><Images size={16} /><span>Extract Content</span></TabsTrigger>
                <TabsTrigger value="convert"><Sparkle size={16} /><span>Convert to Editable</span></TabsTrigger>
              </TabsList>
            </div>

            <TabsContent value="organize" className="pdf-tab-panel">
              <PdfOrganizeTab
                docs={docs}
                pages={pages}
                rangeExpr={rangeExpr}
                setRangeExpr={setRangeExpr}
                scrubMetadata={scrubMetadata}
                setScrubMetadata={setScrubMetadata}
                pageNumbers={pageNumbers}
                setPageNumbers={setPageNumbers}
                busy={busy}
                onApplyRange={applyRangeFilter}
                onResetPages={resetPagesOrder}
                onMovePage={movePage}
                onRotatePage={index => setPages(prev => prev.map((p, i) => (i === index ? { ...p, rotation: (p.rotation + 90) % 360 } : p)))}
                onRemovePage={index => setPages(prev => prev.filter((_, i) => i !== index))}
                onExport={() => void runAssemble()}
              />
            </TabsContent>

            <TabsContent value="inspect" className="pdf-tab-panel">
              <PdfInspectTab
                docs={docs}
                activeDoc={activeDoc}
                selectedDocUid={selectedDocUid}
                setSelectedDocUid={setSelectedDocUid}
                scrubMetadata={scrubMetadata}
                setScrubMetadata={setScrubMetadata}
                stripAnnotations={stripAnnotations}
                setStripAnnotations={setStripAnnotations}
                pageNumbers={pageNumbers}
                setPageNumbers={setPageNumbers}
                watermarkText={watermarkText}
                setWatermarkText={setWatermarkText}
                watermarkOpacity={watermarkOpacity}
                setWatermarkOpacity={setWatermarkOpacity}
                outputName={outputName}
                setOutputName={setOutputName}
                busy={busy}
                onApplyPrivacy={doc => void runAssemble({
                  files: [doc.file],
                  passwords: [doc.password ?? ''],
                  specs: doc.inspection!.pages.map(p => ({ fileIndex: 0, pageNumber: p.number, rotation: 0 })),
                  name: outputName || `${doc.file.name.replace(/\.pdf$/i, '')}-cleaned.pdf`,
                })}
              />
            </TabsContent>

            <TabsContent value="extract" className="pdf-tab-panel">
              <PdfExtractTab
                docs={docs}
                activeDoc={activeDoc}
                selectedDocUid={selectedDocUid}
                setSelectedDocUid={setSelectedDocUid}
                extractResult={extractResult}
                copiedExtract={copiedExtract}
                busy={busy}
                onExtractTextOrMarkdown={kind => void runTextOrMarkdown(kind)}
                onExtractImages={() => void runImageExtract()}
                onCopyExtract={() => void copyExtractContent()}
              />
            </TabsContent>

            <TabsContent value="convert" className="pdf-tab-panel">
              <PdfToSourceTab
                docs={docs}
                activeDoc={activeDoc}
                selectedDocUid={selectedDocUid}
                setSelectedDocUid={setSelectedDocUid}
                busy={busy}
                onBusyChange={setBusyMessage}
                onError={setError}
              />
            </TabsContent>
          </Tabs>
        </div>
      )}
    </section>
  )
}
