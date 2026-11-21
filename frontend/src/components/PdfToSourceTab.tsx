import { useState } from 'react'
import { ArrowDown, Check, Copy, FileText, Info, Sparkle } from '@phosphor-icons/react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { convertPdfToSource, downloadPdfToSource, type PdfToSourceResult } from '@/ecosystemApi'
import { downloadBlob, type LoadedPdf } from '@/pdfApi'

interface PdfToSourceTabProps {
  docs: LoadedPdf[]
  activeDoc: LoadedPdf | null
  selectedDocUid: string
  setSelectedDocUid: (uid: string) => void
  busy: boolean
  onBusyChange: (msg: string) => void
  onError: (msg: string) => void
}

export function PdfToSourceTab({
  docs,
  activeDoc,
  selectedDocUid,
  setSelectedDocUid,
  busy,
  onBusyChange,
  onError,
}: PdfToSourceTabProps) {
  const [targetFormat, setTargetFormat] = useState<'markdown' | 'latex'>('markdown')
  const [useAiLayout, setUseAiLayout] = useState(true)
  const [result, setResult] = useState<{ docUid: string; data: PdfToSourceResult } | null>(null)
  const [downloadWarnings, setDownloadWarnings] = useState<{ docUid: string; items: string[] } | null>(null)
  const [copied, setCopied] = useState(false)

  const runConvert = async () => {
    if (!activeDoc) return
    onError('')
    setDownloadWarnings(null)
    onBusyChange(`Converting ${activeDoc.file.name} to editable ${targetFormat === 'latex' ? 'LaTeX' : 'Markdown'}…`)
    try {
      const data = await convertPdfToSource(activeDoc.file, targetFormat, useAiLayout, activeDoc.password)
      setResult({ docUid: activeDoc.uid, data })
      toast.success(`Converted ${activeDoc.file.name} to ${targetFormat === 'latex' ? 'LaTeX' : 'Markdown'}`)
    } catch (failure) {
      onError(failure instanceof Error ? failure.message : 'Could not convert this PDF.')
    } finally {
      onBusyChange('')
    }
  }

  const runDownload = async (downloadFormat: 'markdown' | 'latex-zip' | 'docx') => {
    if (!activeDoc) return
    onError('')
    onBusyChange(`Preparing ${downloadFormat.toUpperCase()} download…`)
    try {
      const dl = await downloadPdfToSource(activeDoc.file, downloadFormat, useAiLayout, activeDoc.password)
      downloadBlob(dl.blob, dl.filename)
      if (dl.warnings) {
        const items = dl.warnings.split(' | ').map(s => s.trim()).filter(Boolean)
        setDownloadWarnings({ docUid: activeDoc.uid, items })
        toast.warning(`Downloaded ${dl.filename} with notes: ${dl.warnings}`)
      } else {
        setDownloadWarnings(null)
        toast.success(`Downloaded ${dl.filename}`)
      }
    } catch (failure) {
      onError(failure instanceof Error ? failure.message : 'Could not download converted document.')
    } finally {
      onBusyChange('')
    }
  }

  const copySource = async () => {
    if (!result) return
    try {
      await navigator.clipboard.writeText(result.data.content)
      setCopied(true)
      toast.success('Converted source copied')
      setTimeout(() => setCopied(false), 1800)
    } catch {
      onError('Clipboard access is unavailable. Use Download instead.')
    }
  }

  const activeResult = result && result.docUid === activeDoc?.uid ? result.data : null
  const displayedWarnings = Array.from(new Set([
    ...(downloadWarnings && downloadWarnings.docUid === activeDoc?.uid ? downloadWarnings.items : []),
    ...(activeResult?.warnings ?? []),
  ]))

  return (
    <div className="eco-stack">
      {docs.length > 1 && (
        <div className="pdf-doc-selector">
          <label htmlFor="pdf-tosrc-doc-select">Document to convert</label>
          <select
            id="pdf-tosrc-doc-select"
            value={activeDoc?.uid ?? selectedDocUid}
            onChange={event => setSelectedDocUid(event.target.value)}
          >
            {docs.map(doc => (
              <option key={doc.uid} value={doc.uid}>{doc.file.name}</option>
            ))}
          </select>
        </div>
      )}

      <div className="pdf-extract-actions">
        <div>
          <h2>Convert digital PDF to editable source</h2>
          <p>Detects headings, multi-column reading order, strips running headers/footers, and pulls embedded figures.</p>
        </div>
        <div className="eco-controls-row">
          <label className="eco-select-label">
            <span>Target</span>
            <select
              aria-label="Editable target format"
              value={targetFormat}
              disabled={busy}
              onChange={event => setTargetFormat(event.target.value as 'markdown' | 'latex')}
            >
              <option value="markdown">Markdown (.md)</option>
              <option value="latex">LaTeX (.tex)</option>
            </select>
          </label>
          <label className="pdf-inline-check">
            <input
              type="checkbox"
              checked={useAiLayout}
              disabled={busy}
              onChange={event => setUseAiLayout(event.target.checked)}
            />
            <span>Use PP-DocLayout-S AI when ready</span>
          </label>
          <Button className="pdf-primary-cta" disabled={busy || !activeDoc} onClick={() => void runConvert()}>
            <Sparkle size={15} weight="bold" />
            Convert PDF
          </Button>
        </div>
      </div>

      {activeResult ? (
        <div className="eco-stack">
          <div className="review-metrics">
            <div><strong>{activeResult.pageCount}</strong><span>Pages</span></div>
            <div><strong>{activeResult.headingsFound}</strong><span>Headings</span></div>
            <div><strong>{activeResult.imagesExtracted}</strong><span>Images</span></div>
            <div><strong>{activeResult.twoColumnDetected ? '2-column' : 'Single'}</strong><span>Columns</span></div>
            <div><strong>{activeResult.aiLayoutUsed ? 'ONNX AI' : 'Heuristic'}</strong><span>Layout pass</span></div>
            <div><strong>{activeResult.format.toUpperCase()}</strong><span>Output</span></div>
          </div>

          {displayedWarnings.length > 0 && (
            <div className="pdf-warnings-box" aria-label="Conversion notes">
              <h3>Conversion notes</h3>
              <ul>
                {displayedWarnings.map((w, idx) => (
                  <li key={idx}><Info size={15} /><span>{w}</span></li>
                ))}
              </ul>
            </div>
          )}

          <div className="pdf-extract-output">
            <div className="pdf-extract-bar">
              <span>{activeResult.filename} → {activeResult.format}</span>
              <div className="eco-btn-group">
                <Button variant="outline" size="sm" onClick={() => void copySource()}>
                  {copied ? <Check size={14} /> : <Copy size={14} />}
                  {copied ? 'Copied' : 'Copy'}
                </Button>
                <Button variant="outline" size="sm" disabled={busy} onClick={() => void runDownload('markdown')}>
                  <ArrowDown size={14} />
                  .md
                </Button>
                <Button variant="outline" size="sm" disabled={busy} onClick={() => void runDownload('latex-zip')}>
                  <ArrowDown size={14} />
                  LaTeX + media (.zip)
                </Button>
                <Button variant="outline" size="sm" disabled={busy} onClick={() => void runDownload('docx')}>
                  <FileText size={14} />
                  Word (.docx)
                </Button>
              </div>
            </div>
            <textarea
              readOnly
              aria-label="Converted PDF source preview"
              value={activeResult.content}
              className="pdf-extract-textarea"
            />
          </div>
        </div>
      ) : (
        <div className="pdf-empty-pages">
          <p>Select a target format and click <strong>Convert PDF</strong> to generate editable Markdown, LaTeX + media ZIP, or DOCX.</p>
        </div>
      )}
    </div>
  )
}
