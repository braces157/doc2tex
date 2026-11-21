import { FilePdf, Lock, X } from '@phosphor-icons/react'
import { formatBytes } from '@/api'
import { Button } from '@/components/ui/button'
import type { LoadedPdf } from '@/pdfApi'

interface PdfLoadedStripProps {
  docs: LoadedPdf[]
  totalLoadedBytes: number
  busy: boolean
  passwordDrafts: Record<string, string>
  setPasswordDrafts: (updater: (prev: Record<string, string>) => Record<string, string>) => void
  onUnlock: (doc: LoadedPdf, password: string) => void
  onRemove: (uid: string) => void
}

export function PdfLoadedStrip({
  docs,
  totalLoadedBytes,
  busy,
  passwordDrafts,
  setPasswordDrafts,
  onUnlock,
  onRemove,
}: PdfLoadedStripProps) {
  return (
    <section className="pdf-loaded-strip" aria-label="Loaded PDF files">
      <div className="pdf-loaded-head">
        <h2>Loaded PDFs ({docs.length})</h2>
        <span>{formatBytes(totalLoadedBytes)} of 50 MB limit</span>
      </div>
      <div className="pdf-chip-list">
        {docs.map(doc => (
          <div key={doc.uid} className={`pdf-file-chip ${doc.error ? 'has-error' : ''}`}>
            <FilePdf size={17} />
            <div className="pdf-file-meta">
              <strong title={doc.file.name}>{doc.file.name}</strong>
              <small>
                {formatBytes(doc.file.size)}
                {doc.loading
                  ? ' · Inspecting…'
                  : doc.inspection
                    ? ` · ${doc.inspection.pageCount} page${doc.inspection.pageCount === 1 ? '' : 's'}`
                    : doc.error
                      ? ` · ${doc.error}`
                      : ''}
              </small>
              {doc.needsPassword && (
                <form
                  className="pdf-unlock-form"
                  onSubmit={event => {
                    event.preventDefault()
                    onUnlock(doc, passwordDrafts[doc.uid] || '')
                  }}
                >
                  <Lock size={14} />
                  <input
                    type="password"
                    aria-label={`Password for ${doc.file.name}`}
                    placeholder="Enter PDF password"
                    value={passwordDrafts[doc.uid] || ''}
                    onChange={event => setPasswordDrafts(prev => ({ ...prev, [doc.uid]: event.target.value }))}
                  />
                  <Button type="submit" size="sm" variant="outline">Unlock</Button>
                </form>
              )}
            </div>
            <Button
              variant="ghost"
              size="icon-sm"
              aria-label={`Remove ${doc.file.name}`}
              disabled={busy}
              onClick={() => onRemove(doc.uid)}
            >
              <X size={15} />
            </Button>
          </div>
        ))}
      </div>
    </section>
  )
}
