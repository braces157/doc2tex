import { Info, ShieldCheck, SlidersHorizontal } from '@phosphor-icons/react'
import { Button } from '@/components/ui/button'
import type { LoadedPdf } from '@/pdfApi'

interface PdfInspectTabProps {
  docs: LoadedPdf[]
  activeDoc: LoadedPdf | null
  selectedDocUid: string
  setSelectedDocUid: (uid: string) => void
  scrubMetadata: boolean
  setScrubMetadata: (value: boolean) => void
  stripAnnotations: boolean
  setStripAnnotations: (value: boolean) => void
  pageNumbers: boolean
  setPageNumbers: (value: boolean) => void
  watermarkText: string
  setWatermarkText: (value: string) => void
  watermarkOpacity: number
  setWatermarkOpacity: (value: number) => void
  outputName: string
  setOutputName: (value: string) => void
  busy: boolean
  onApplyPrivacy: (doc: LoadedPdf) => void
}

export function PdfInspectTab({
  docs,
  activeDoc,
  selectedDocUid,
  setSelectedDocUid,
  scrubMetadata,
  setScrubMetadata,
  stripAnnotations,
  setStripAnnotations,
  pageNumbers,
  setPageNumbers,
  watermarkText,
  setWatermarkText,
  watermarkOpacity,
  setWatermarkOpacity,
  outputName,
  setOutputName,
  busy,
  onApplyPrivacy,
}: PdfInspectTabProps) {
  return (
    <>
      {docs.length > 1 && (
        <div className="pdf-doc-selector">
          <label htmlFor="inspect-doc-select">Document to inspect</label>
          <select
            id="inspect-doc-select"
            value={activeDoc?.uid ?? selectedDocUid}
            onChange={event => setSelectedDocUid(event.target.value)}
          >
            {docs.map(doc => (
              <option key={doc.uid} value={doc.uid}>{doc.file.name}</option>
            ))}
          </select>
        </div>
      )}

      {activeDoc?.inspection ? (
        <div className="pdf-inspect-layout">
          <div>
            <div className="pdf-section-title">
              <h2>Inspection summary</h2>
              <span>PDF {activeDoc.inspection.pdfVersion}</span>
            </div>
            <div className="review-metrics">
              <div><strong>{activeDoc.inspection.pageCount}</strong><span>Pages</span></div>
              <div><strong>{activeDoc.inspection.fontCount}</strong><span>Fonts</span></div>
              <div><strong>{activeDoc.inspection.embeddedImageCount}</strong><span>Images</span></div>
              <div><strong>{activeDoc.inspection.annotationCount}</strong><span>Annotations</span></div>
              <div>
                <strong>{activeDoc.inspection.hasMetadata || activeDoc.inspection.hasXmpMetadata ? 'Present' : 'Clean'}</strong>
                <span>Metadata</span>
              </div>
              <div><strong>{activeDoc.inspection.encrypted ? 'Locked' : 'Open'}</strong><span>Encryption</span></div>
            </div>

            {activeDoc.inspection.warnings.length > 0 && (
              <div className="pdf-warnings-box" aria-label="Inspection warnings">
                <h3>Findings</h3>
                <ul>
                  {activeDoc.inspection.warnings.map((warning, idx) => (
                    <li key={idx}><Info size={15} /><span>{warning}</span></li>
                  ))}
                </ul>
              </div>
            )}

            <div className="pdf-metadata-card">
              <h3>Document metadata</h3>
              <dl className="pdf-metadata-grid">
                <dt>Title</dt><dd>{activeDoc.inspection.metadata.title || '—'}</dd>
                <dt>Author</dt><dd>{activeDoc.inspection.metadata.author || '—'}</dd>
                <dt>Creator</dt><dd>{activeDoc.inspection.metadata.creator || '—'}</dd>
                <dt>Producer</dt><dd>{activeDoc.inspection.metadata.producer || '—'}</dd>
                <dt>Subject</dt><dd>{activeDoc.inspection.metadata.subject || '—'}</dd>
                <dt>Keywords</dt><dd>{activeDoc.inspection.metadata.keywords || '—'}</dd>
                <dt>XMP stream</dt><dd>{activeDoc.inspection.hasXmpMetadata ? 'Present' : 'None'}</dd>
              </dl>
            </div>

            {activeDoc.inspection.outline.length > 0 && (
              <details className="pdf-outline-details">
                <summary>Bookmarks ({activeDoc.inspection.outline.length})</summary>
                <ul>
                  {activeDoc.inspection.outline.map((item, idx) => (
                    <li key={idx} style={{ paddingLeft: `${Math.min(item.level, 4) * 14}px` }}>
                      <span>{item.title}</span>
                      {item.pageNumber !== null && <small>p.{item.pageNumber}</small>}
                    </li>
                  ))}
                </ul>
              </details>
            )}
          </div>

          <div className="pdf-privacy-panel">
            <div className="pdf-section-title">
              <h3>Privacy &amp; preparation</h3>
              <SlidersHorizontal size={16} />
            </div>
            <p className="pdf-privacy-copy">
              Clean hidden author fields, remove annotations, or stamp a diagonal watermark and footer page numbers on{' '}
              <strong>{activeDoc.file.name}</strong>.
            </p>
            <label className="pdf-check-row">
              <input
                type="checkbox"
                checked={scrubMetadata}
                onChange={event => setScrubMetadata(event.target.checked)}
              />
              <span>
                <strong>Scrub all metadata &amp; XMP</strong>
                <small>Removes Author, Creator, Producer, dates, and XMP streams for double-blind review.</small>
              </span>
            </label>
            <label className="pdf-check-row">
              <input
                type="checkbox"
                checked={stripAnnotations}
                onChange={event => setStripAnnotations(event.target.checked)}
              />
              <span>
                <strong>Strip annotations &amp; comments</strong>
                <small>Removes PDF sticky notes, highlights, and comment popups.</small>
              </span>
            </label>
            <label className="pdf-check-row">
              <input
                type="checkbox"
                checked={pageNumbers}
                onChange={event => setPageNumbers(event.target.checked)}
              />
              <span>
                <strong>Add footer page numbers</strong>
                <small>Stamps centered “1 / N” numbering near the bottom margin.</small>
              </span>
            </label>
            <div className="pdf-field-group">
              <label htmlFor="pdf-watermark-text">Diagonal watermark (optional)</label>
              <input
                id="pdf-watermark-text"
                type="text"
                maxLength={120}
                placeholder="e.g. CONFIDENTIAL or DRAFT"
                value={watermarkText}
                onChange={event => setWatermarkText(event.target.value)}
              />
            </div>
            {watermarkText.trim() && (
              <div className="pdf-field-group">
                <label htmlFor="pdf-watermark-opacity">
                  Watermark opacity ({Math.round(watermarkOpacity * 100)}%)
                </label>
                <input
                  id="pdf-watermark-opacity"
                  type="range"
                  min={0.08}
                  max={0.5}
                  step={0.02}
                  value={watermarkOpacity}
                  onChange={event => setWatermarkOpacity(Number(event.target.value))}
                />
              </div>
            )}
            <div className="pdf-field-group">
              <label htmlFor="pdf-output-name">Output filename</label>
              <input
                id="pdf-output-name"
                type="text"
                maxLength={120}
                value={outputName}
                onChange={event => setOutputName(event.target.value)}
              />
            </div>
            <Button
              className="pdf-primary-cta w-full"
              disabled={busy || activeDoc.inspection.pageCount === 0}
              onClick={() => onApplyPrivacy(activeDoc)}
            >
              <ShieldCheck size={16} weight="bold" />
              Apply &amp; download PDF
            </Button>
          </div>
        </div>
      ) : (
        <p className="pdf-empty-pages">Select a valid, unlocked PDF to inspect its metadata and structure.</p>
      )}
    </>
  )
}
