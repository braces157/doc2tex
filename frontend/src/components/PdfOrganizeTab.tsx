import { ArrowClockwise, ArrowDown, ArrowLeft, ArrowRight, Trash } from '@phosphor-icons/react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { PdfPageThumb } from '@/PdfThumbnails'
import type { LoadedPdf, OrganizerPage } from '@/pdfApi'

interface PdfOrganizeTabProps {
  docs: LoadedPdf[]
  pages: OrganizerPage[]
  rangeExpr: string
  setRangeExpr: (value: string) => void
  scrubMetadata: boolean
  setScrubMetadata: (value: boolean) => void
  pageNumbers: boolean
  setPageNumbers: (value: boolean) => void
  busy: boolean
  onApplyRange: () => void
  onResetPages: () => void
  onMovePage: (index: number, delta: -1 | 1) => void
  onRotatePage: (index: number) => void
  onRemovePage: (index: number) => void
  onExport: () => void
}

export function PdfOrganizeTab({
  docs,
  pages,
  rangeExpr,
  setRangeExpr,
  scrubMetadata,
  setScrubMetadata,
  pageNumbers,
  setPageNumbers,
  busy,
  onApplyRange,
  onResetPages,
  onMovePage,
  onRotatePage,
  onRemovePage,
  onExport,
}: PdfOrganizeTabProps) {
  return (
    <>
      <div className="pdf-organize-toolbar">
        <div className="pdf-organize-summary">
          <h2>Page organizer</h2>
          <Badge variant="outline">
            {pages.length} page{pages.length === 1 ? '' : 's'} in output
          </Badge>
        </div>
        <div className="pdf-organize-controls">
          <div className="pdf-range-box">
            <label htmlFor="pdf-range-input" className="sr-only">Keep page range</label>
            <input
              id="pdf-range-input"
              type="text"
              placeholder="Range e.g. 1-3, 5"
              value={rangeExpr}
              onChange={event => setRangeExpr(event.target.value)}
            />
            <Button variant="outline" size="sm" disabled={busy || pages.length === 0} onClick={onApplyRange}>
              Keep range
            </Button>
            <Button
              variant="ghost"
              size="sm"
              disabled={busy || docs.every(doc => !doc.inspection)}
              onClick={onResetPages}
            >
              Reset
            </Button>
          </div>
          <div className="pdf-export-controls">
            <label className="pdf-inline-check">
              <input
                type="checkbox"
                checked={scrubMetadata}
                onChange={event => setScrubMetadata(event.target.checked)}
              />
              <span>Scrub metadata</span>
            </label>
            <label className="pdf-inline-check">
              <input
                type="checkbox"
                checked={pageNumbers}
                onChange={event => setPageNumbers(event.target.checked)}
              />
              <span>Page numbers</span>
            </label>
            <Button className="pdf-primary-cta" disabled={busy || pages.length === 0} onClick={onExport}>
              <ArrowDown size={15} weight="bold" />
              Export assembled PDF
            </Button>
          </div>
        </div>
      </div>

      {pages.length === 0 ? (
        <div className="pdf-empty-pages">
          <p>
            No pages selected. Click <strong>Reset</strong> to restore pages from your loaded PDFs.
          </p>
        </div>
      ) : (
        <div className="pdf-page-grid" role="list" aria-label="PDF pages">
          {pages.map((page, index) => {
            const sourceDoc = docs.find(doc => doc.uid === page.docUid)
            return (
              <article key={page.key} className="pdf-page-card" role="listitem">
                <div className="pdf-page-card-head">
                  <span className="pdf-page-index">#{index + 1}</span>
                  <span className="pdf-page-origin" title={`${page.docName} · p.${page.pageNumber}`}>
                    {page.docName} · p.{page.pageNumber}
                  </span>
                  {page.rotation !== 0 && (
                    <Badge variant="outline" className="pdf-rot-badge">{page.rotation}°</Badge>
                  )}
                </div>
                {sourceDoc && (
                  <PdfPageThumb
                    file={sourceDoc.file}
                    password={sourceDoc.password}
                    pageNumber={page.pageNumber}
                    rotation={page.rotation}
                    label={`Output page ${index + 1}, from ${page.docName} page ${page.pageNumber}`}
                  />
                )}
                <div className="pdf-page-card-meta">
                  <span>{page.widthMm} × {page.heightMm} mm</span>
                </div>
                <div className="pdf-page-card-actions">
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    aria-label={`Move page ${index + 1} earlier`}
                    disabled={busy || index === 0}
                    onClick={() => onMovePage(index, -1)}
                  >
                    <ArrowLeft size={14} />
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    aria-label={`Move page ${index + 1} later`}
                    disabled={busy || index === pages.length - 1}
                    onClick={() => onMovePage(index, 1)}
                  >
                    <ArrowRight size={14} />
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    aria-label={`Rotate page ${index + 1} clockwise`}
                    disabled={busy}
                    onClick={() => onRotatePage(index)}
                  >
                    <ArrowClockwise size={14} />
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    aria-label={`Remove page ${index + 1}`}
                    disabled={busy}
                    onClick={() => onRemovePage(index)}
                  >
                    <Trash size={14} />
                  </Button>
                </div>
              </article>
            )
          })}
        </div>
      )}
    </>
  )
}
