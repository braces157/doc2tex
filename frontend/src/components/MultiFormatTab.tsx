import { useRef, useState } from 'react'
import { ArrowDown, ArrowRight, Check, Copy, FileText, Info, UploadSimple } from '@phosphor-icons/react'
import { toast } from 'sonner'
import { formatBytes } from '@/api'
import { Button } from '@/components/ui/button'
import { convertFile, type FormatCatalog } from '@/ecosystemApi'
import { downloadBlob } from '@/pdfApi'

interface MultiFormatTabProps {
  catalog: FormatCatalog | null
  busy: boolean
  onBusyChange: (msg: string) => void
  onError: (msg: string) => void
}

export function MultiFormatTab({ catalog, busy, onBusyChange, onError }: MultiFormatTabProps) {
  const inputRef = useRef<HTMLInputElement>(null)
  const [file, setFile] = useState<File | null>(null)
  const [targetFormat, setTargetFormat] = useState('docx')
  const [toc, setToc] = useState(false)
  const [dragging, setDragging] = useState(false)
  const [previewText, setPreviewText] = useState<{ filename: string; text: string; warnings: string } | null>(null)
  const [copied, setCopied] = useState(false)

  const selectFile = (next: File | undefined) => {
    if (!next) return
    onError('')
    if (next.size === 0 || next.size > 20 * 1024 * 1024) {
      onError('Choose a non-empty document up to 20 MB.')
      return
    }
    setFile(next)
    setPreviewText(null)
  }

  const runConvert = async () => {
    if (!file) return
    onError('')
    onBusyChange(`Converting ${file.name} to ${targetFormat}…`)
    try {
      const result = await convertFile(file, targetFormat, toc)
      downloadBlob(result.blob, result.filename)
      toast.success(`Converted to ${result.filename}`)
      if (['markdown', 'html', 'latex', 'beamer', 'typst'].includes(targetFormat) && result.blob.size < 1024 * 1024) {
        setPreviewText({
          filename: result.filename,
          text: await result.blob.text(),
          warnings: result.warnings,
        })
      } else {
        setPreviewText(result.warnings ? { filename: result.filename, text: '', warnings: result.warnings } : null)
      }
    } catch (failure) {
      onError(failure instanceof Error ? failure.message : 'Format conversion failed.')
    } finally {
      onBusyChange('')
    }
  }

  const copyPreview = async () => {
    if (!previewText?.text) return
    try {
      await navigator.clipboard.writeText(previewText.text)
      setCopied(true)
      toast.success('Converted output copied')
      setTimeout(() => setCopied(false), 1800)
    } catch {
      onError('Clipboard access is unavailable.')
    }
  }

  const outputs = catalog?.outputs ?? [
    { id: 'docx', label: 'Word Document (.docx)', extension: '.docx' },
    { id: 'markdown', label: 'Markdown (.md)', extension: '.md' },
    { id: 'html', label: 'Self-contained HTML (.html)', extension: '.html' },
    { id: 'epub', label: 'EPUB 3 E-Book (.epub)', extension: '.epub' },
    { id: 'odt', label: 'OpenDocument (.odt)', extension: '.odt' },
    { id: 'rtf', label: 'Rich Text (.rtf)', extension: '.rtf' },
    { id: 'latex', label: 'LaTeX (.tex)', extension: '.tex' },
    { id: 'beamer', label: 'Beamer Slides (.tex)', extension: '.tex' },
    { id: 'pptx', label: 'PowerPoint (.pptx)', extension: '.pptx' },
    { id: 'typst', label: 'Typst (.typ)', extension: '.typ' },
  ]

  return (
    <div className="eco-stack">
      <input
        ref={inputRef}
        type="file"
        className="sr-only"
        aria-label="Upload document for format conversion"
        accept=".docx,.doc,.md,.markdown,.html,.htm,.epub,.odt,.rtf,.tex,.latex,.rst,.typ"
        onChange={event => {
          selectFile(event.target.files?.[0])
          event.target.value = ''
        }}
      />

      <div className="eco-two-col">
        <div
          className={`pdf-studio-dropzone eco-compact-drop ${dragging ? 'is-dragging' : ''}`}
          onDragOver={event => { event.preventDefault(); setDragging(true) }}
          onDragLeave={() => setDragging(false)}
          onDrop={event => {
            event.preventDefault()
            setDragging(false)
            selectFile(event.dataTransfer.files[0])
          }}
        >
          <div className="ui-upload-icon"><FileText size={24} /><span>DOC</span></div>
          <h2>{file ? file.name : 'Drop any supported document'}</h2>
          <p>{file ? `${formatBytes(file.size)} · Ready to convert` : 'DOCX, Markdown, HTML, EPUB, ODT, RTF, LaTeX, RST, Typst, or legacy .doc.'}</p>
          <Button variant="outline" disabled={busy} onClick={() => inputRef.current?.click()}>
            <UploadSimple size={15} />
            {file ? 'Choose another file' : 'Choose document'}
          </Button>
          <span className="ui-upload-limit">Up to 20 MB · runs offline in Pandoc sandbox</span>
        </div>

        <div className="pdf-privacy-panel">
          <h3>Conversion settings</h3>
          <div className="pdf-field-group">
            <label htmlFor="hub-target-format">Output format</label>
            <select
              id="hub-target-format"
              className="eco-select"
              value={targetFormat}
              disabled={busy}
              onChange={event => setTargetFormat(event.target.value)}
            >
              {outputs.map(opt => (
                <option key={opt.id} value={opt.id}>{opt.label}</option>
              ))}
            </select>
          </div>
          <label className="pdf-check-row">
            <input type="checkbox" checked={toc} disabled={busy} onChange={event => setToc(event.target.checked)} />
            <span>
              <strong>Include table of contents</strong>
              <small>Adds a structured TOC when supported by the target format.</small>
            </span>
          </label>
          <Button
            className="pdf-primary-cta w-full"
            disabled={!file || busy || catalog?.pandocReady === false}
            onClick={() => void runConvert()}
          >
            Convert &amp; download
            <ArrowRight size={15} weight="bold" />
          </Button>
          {catalog && !catalog.legacyDocReady && (
            <p className="eco-hint">
              <Info size={14} />
              <span>Legacy <code>.doc</code> files require LibreOffice (see Setup diagnostics). All other formats use Pandoc directly.</span>
            </p>
          )}
        </div>
      </div>

      {previewText && (
        <div className="pdf-extract-output">
          <div className="pdf-extract-bar">
            <span>{previewText.filename}{previewText.warnings ? ` · ${previewText.warnings}` : ''}</span>
            {previewText.text && (
              <div className="eco-btn-group">
                <Button variant="outline" size="sm" onClick={() => void copyPreview()}>
                  {copied ? <Check size={14} /> : <Copy size={14} />}
                  {copied ? 'Copied' : 'Copy'}
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => downloadBlob(new Blob([previewText.text], { type: 'text/plain;charset=utf-8' }), previewText.filename)}
                >
                  <ArrowDown size={14} />
                  Save copy
                </Button>
              </div>
            )}
          </div>
          {previewText.text && (
            <textarea readOnly aria-label="Converted text preview" value={previewText.text} className="pdf-extract-textarea" />
          )}
        </div>
      )}
    </div>
  )
}
