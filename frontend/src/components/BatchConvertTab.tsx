import { useRef, useState } from 'react'
import { ArrowDown, Stack, Trash, UploadSimple } from '@phosphor-icons/react'
import { toast } from 'sonner'
import { formatBytes } from '@/api'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { runBatchConvert, type BatchDownload, type FormatCatalog } from '@/ecosystemApi'
import { downloadBlob } from '@/pdfApi'

interface BatchConvertTabProps {
  catalog: FormatCatalog | null
  busy: boolean
  onBusyChange: (msg: string) => void
  onError: (msg: string) => void
}

export function BatchConvertTab({ catalog, busy, onBusyChange, onError }: BatchConvertTabProps) {
  const inputRef = useRef<HTMLInputElement>(null)
  const [files, setFiles] = useState<File[]>([])
  const [targetFormat, setTargetFormat] = useState('markdown')
  const [toc, setToc] = useState(false)
  const [lastBatch, setLastBatch] = useState<BatchDownload | null>(null)

  const totalBytes = files.reduce((sum, f) => sum + f.size, 0)

  const addFiles = (incomingList: FileList | File[]) => {
    onError('')
    const incoming = Array.from(incomingList)
    const next = [...files]
    let running = totalBytes
    for (const file of incoming) {
      if (next.length >= 20) {
        onError('Batch queue supports up to 20 files at once.')
        break
      }
      if (file.size === 0 || file.size > 20 * 1024 * 1024) {
        onError(`"${file.name}" must be between 1 byte and 20 MB.`)
        continue
      }
      if (running + file.size > 50 * 1024 * 1024) {
        onError('Combined batch files cannot exceed 50 MB.')
        break
      }
      running += file.size
      next.push(file)
    }
    setFiles(next)
    setLastBatch(null)
  }

  const runBatch = async () => {
    if (files.length === 0) return
    onError('')
    onBusyChange(`Processing ${files.length} files (${targetFormat})…`)
    try {
      const result = await runBatchConvert(files, targetFormat, toc)
      setLastBatch(result)
      downloadBlob(result.blob, result.filename)
      toast.success(`Batch finished: ${result.succeeded}/${result.total} succeeded`)
    } catch (failure) {
      onError(failure instanceof Error ? failure.message : 'Batch processing failed.')
    } finally {
      onBusyChange('')
    }
  }

  const outputs = catalog?.outputs ?? [
    { id: 'markdown', label: 'Markdown (.md)', extension: '.md' },
    { id: 'docx', label: 'Word (.docx)', extension: '.docx' },
    { id: 'html', label: 'HTML (.html)', extension: '.html' },
    { id: 'latex', label: 'LaTeX (.tex)', extension: '.tex' },
    { id: 'typst', label: 'Typst (.typ)', extension: '.typ' },
  ]

  return (
    <div className="eco-stack">
      <input
        ref={inputRef}
        type="file"
        multiple
        className="sr-only"
        aria-label="Upload multiple files for batch conversion"
        onChange={event => {
          if (event.target.files) addFiles(event.target.files)
          event.target.value = ''
        }}
      />

      <div className="pdf-extract-actions">
        <div>
          <h2>Batch queue</h2>
          <p>Convert up to 20 documents or merge multiple PDFs into one ZIP. Corrupt files fail individually without blocking healthy siblings.</p>
        </div>
        <div className="eco-controls-row">
          <label className="eco-select-label">
            <span>Batch action</span>
            <select
              aria-label="Batch target format"
              value={targetFormat}
              disabled={busy}
              onChange={event => setTargetFormat(event.target.value)}
            >
              {outputs.map(opt => (
                <option key={opt.id} value={opt.id}>Convert all → {opt.label}</option>
              ))}
              <option value="pdf-merge">Merge all PDFs → single PDF</option>
            </select>
          </label>
          {targetFormat !== 'pdf-merge' && (
            <label className="pdf-inline-check">
              <input type="checkbox" checked={toc} disabled={busy} onChange={event => setToc(event.target.checked)} />
              <span>Include TOC</span>
            </label>
          )}
          <Button variant="outline" disabled={busy} onClick={() => inputRef.current?.click()}>
            <UploadSimple size={15} />
            Add files
          </Button>
          <Button className="pdf-primary-cta" disabled={busy || files.length === 0} onClick={() => void runBatch()}>
            <Stack size={15} weight="bold" />
            Run batch ZIP
          </Button>
        </div>
      </div>

      {lastBatch && (
        <div className="eco-summary-banner" role="status">
          <span>
            Last batch <strong>{lastBatch.filename}</strong>: {lastBatch.succeeded} succeeded, {lastBatch.failed} failed out of {lastBatch.total} (see <code>batch-report.json</code> inside ZIP).
          </span>
          <Button variant="outline" size="sm" onClick={() => downloadBlob(lastBatch.blob, lastBatch.filename)}>
            <ArrowDown size={14} />
            Download again
          </Button>
        </div>
      )}

      {files.length === 0 ? (
        <div className="pdf-empty-pages">
          <p>Click <strong>Add files</strong> to queue multiple Word, Markdown, HTML, LaTeX, or PDF documents.</p>
        </div>
      ) : (
        <div className="eco-file-list" role="list" aria-label="Queued batch files">
          <div className="pdf-loaded-head">
            <span>{files.length} queued file{files.length === 1 ? '' : 's'}</span>
            <span>{formatBytes(totalBytes)} / 50 MB</span>
          </div>
          {files.map((file, idx) => (
            <div key={`${file.name}-${idx}`} className="eco-file-row" role="listitem">
              <div>
                <strong>{file.name}</strong>
                <small>{formatBytes(file.size)}</small>
              </div>
              <div className="eco-btn-group">
                <Badge variant="outline">Queued</Badge>
                <Button
                  variant="ghost"
                  size="icon-sm"
                  aria-label={`Remove ${file.name} from batch`}
                  disabled={busy}
                  onClick={() => setFiles(prev => prev.filter((_, i) => i !== idx))}
                >
                  <Trash size={14} />
                </Button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
