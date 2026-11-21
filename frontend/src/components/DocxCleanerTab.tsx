import { useRef, useState } from 'react'
import { Broom, FileDoc, Info, UploadSimple } from '@phosphor-icons/react'
import { toast } from 'sonner'
import { formatBytes } from '@/api'
import { Button } from '@/components/ui/button'
import { cleanDocx, inspectDocx, type DocxCleanResult, type DocxInspection } from '@/ecosystemApi'
import { downloadBlob } from '@/pdfApi'

interface DocxCleanerTabProps {
  busy: boolean
  onBusyChange: (msg: string) => void
  onError: (msg: string) => void
}

export function DocxCleanerTab({ busy, onBusyChange, onError }: DocxCleanerTabProps) {
  const fileRef = useRef<HTMLInputElement>(null)
  const [file, setFile] = useState<File | null>(null)
  const [inspection, setInspection] = useState<DocxInspection | null>(null)
  const [stripMetadata, setStripMetadata] = useState(true)
  const [stripComments, setStripComments] = useState(true)
  const [compressMedia, setCompressMedia] = useState(true)
  const [maxImagePx, setMaxImagePx] = useState(1800)
  const [lastResult, setLastResult] = useState<DocxCleanResult | null>(null)

  const handleFile = async (next: File | undefined) => {
    if (!next) return
    onError('')
    if (!next.name.toLowerCase().endsWith('.docx') || next.size === 0 || next.size > 20 * 1024 * 1024) {
      onError('Choose a valid .docx file up to 20 MB.')
      return
    }
    setFile(next)
    setLastResult(null)
    onBusyChange(`Inspecting ${next.name}…`)
    try {
      const report = await inspectDocx(next)
      setInspection(report)
    } catch (failure) {
      setInspection(null)
      onError(failure instanceof Error ? failure.message : 'Could not inspect this DOCX file.')
    } finally {
      onBusyChange('')
    }
  }

  const runClean = async () => {
    if (!file) return
    onError('')
    onBusyChange(`Cleaning ${file.name}…`)
    try {
      const res = await cleanDocx(file, { stripMetadata, stripComments, compressMedia, maxImagePx })
      setLastResult(res)
      downloadBlob(res.blob, res.filename)
      toast.success(`Downloaded ${res.filename}`)
    } catch (failure) {
      onError(failure instanceof Error ? failure.message : 'DOCX cleanup failed.')
    } finally {
      onBusyChange('')
    }
  }

  return (
    <div className="eco-stack">
      <input
        ref={fileRef}
        type="file"
        accept=".docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        className="sr-only"
        aria-label="Upload DOCX for pre-flight cleaning"
        onChange={e => { void handleFile(e.target.files?.[0]); e.target.value = '' }}
      />

      <div className="pdf-extract-actions">
        <div>
          <h2>DOCX Pre-Flight Cleaner</h2>
          <p>Audit author properties, review comments, tracked revisions, and oversized images — then export a cleaned OOXML copy.</p>
        </div>
        <Button variant="outline" disabled={busy} onClick={() => fileRef.current?.click()}>
          <UploadSimple size={15} />
          {file ? 'Choose another .docx' : 'Choose .docx file'}
        </Button>
      </div>

      {!inspection ? (
        <div className="pdf-empty-pages">
          <FileDoc size={24} className="mx-auto mb-2" />
          <p>Select a Word <code>.docx</code> document to inspect hidden properties, comments, and embedded media bloat.</p>
        </div>
      ) : (
        <div className="pdf-inspect-layout">
          <div className="eco-stack">
            <div className="review-metrics">
              <div><strong>{formatBytes(inspection.totalBytes)}</strong><span>Archive size</span></div>
              <div><strong>{formatBytes(inspection.mediaBytes)}</strong><span>Media ({inspection.mediaCount})</span></div>
              <div><strong>{inspection.commentCount}</strong><span>Comments</span></div>
              <div><strong>{inspection.trackedChangeCount}</strong><span>Tracked edits</span></div>
              <div><strong>{inspection.externalLinkCount}</strong><span>External links</span></div>
            </div>

            {inspection.recommendations.length > 0 && (
              <div className="pdf-warnings-box" aria-label="DOCX audit recommendations">
                <h3>Pre-flight findings</h3>
                <ul>
                  {inspection.recommendations.map((rec, idx) => (
                    <li key={idx}><Info size={15} /><span>{rec}</span></li>
                  ))}
                </ul>
              </div>
            )}

            <div className="pdf-metadata-card">
              <h3>Properties found ({Object.keys(inspection.metadata).length})</h3>
              {Object.keys(inspection.metadata).length === 0 ? (
                <p className="eco-diff-note">No identifying metadata properties found.</p>
              ) : (
                <dl className="pdf-metadata-grid">
                  {Object.entries(inspection.metadata).map(([k, v]) => (
                    <div key={k} className="contents">
                      <dt>{k}</dt><dd>{v}</dd>
                    </div>
                  ))}
                </dl>
              )}
            </div>
          </div>

          <div className="pdf-privacy-panel">
            <h3>Cleaning actions</h3>
            <label className="pdf-check-row">
              <input type="checkbox" checked={stripMetadata} onChange={e => setStripMetadata(e.target.checked)} />
              <span><strong>Scrub metadata &amp; custom properties</strong><small>Clears author, title, lastModifiedBy, company, custom properties, and thumbnails.</small></span>
            </label>
            <label className="pdf-check-row">
              <input type="checkbox" checked={stripComments} onChange={e => setStripComments(e.target.checked)} />
              <span><strong>Strip review comments</strong><small>Removes <code>word/comments.xml</code> and comment anchors cleanly.</small></span>
            </label>
            <label className="pdf-check-row">
              <input type="checkbox" checked={compressMedia} onChange={e => setCompressMedia(e.target.checked)} />
              <span><strong>Downscale oversized media</strong><small>Re-compresses images larger than 80 KB when smaller bytes result.</small></span>
            </label>
            {compressMedia && (
              <div className="pdf-field-group">
                <label htmlFor="docx-max-px">Max image dimension ({maxImagePx} px)</label>
                <input
                  id="docx-max-px"
                  type="range"
                  min={800}
                  max={2800}
                  step={200}
                  value={maxImagePx}
                  onChange={e => setMaxImagePx(Number(e.target.value))}
                />
              </div>
            )}
            <Button className="pdf-primary-cta w-full" disabled={busy} onClick={() => void runClean()}>
              <Broom size={16} weight="bold" />
              Clean &amp; download .docx
            </Button>
            {lastResult && (
              <p className="eco-summary-banner" role="status">
                Cleaned copy: {formatBytes(lastResult.originalBytes)} → {formatBytes(lastResult.cleanedBytes)} ({lastResult.imagesOptimized} image(s) optimized).
              </p>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
