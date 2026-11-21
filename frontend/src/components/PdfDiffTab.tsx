import { useRef, useState } from 'react'
import { Eye, GitDiff, UploadSimple } from '@phosphor-icons/react'
import { toast } from 'sonner'
import { formatBytes } from '@/api'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { diffPdfs, renderPdfDiffPage, type PdfDiffReport, type VisualPageDiff } from '@/ecosystemApi'

interface PdfDiffTabProps {
  busy: boolean
  onBusyChange: (msg: string) => void
  onError: (msg: string) => void
}

export function PdfDiffTab({ busy, onBusyChange, onError }: PdfDiffTabProps) {
  const leftRef = useRef<HTMLInputElement>(null)
  const rightRef = useRef<HTMLInputElement>(null)
  const [leftFile, setLeftFile] = useState<File | null>(null)
  const [rightFile, setRightFile] = useState<File | null>(null)
  const [report, setReport] = useState<PdfDiffReport | null>(null)
  const [selectedPage, setSelectedPage] = useState(1)
  const [visual, setVisual] = useState<VisualPageDiff | null>(null)

  const selectSide = (side: 'left' | 'right', file: File | undefined) => {
    if (!file) return
    onError('')
    if (!file.name.toLowerCase().endsWith('.pdf') || file.size === 0 || file.size > 20 * 1024 * 1024) {
      onError('Choose a valid non-empty PDF file up to 20 MB.')
      return
    }
    if (side === 'left') setLeftFile(file)
    else setRightFile(file)
    setReport(null)
    setVisual(null)
  }

  const runCompare = async () => {
    if (!leftFile || !rightFile) return
    onError('')
    onBusyChange('Comparing PDF text layers…')
    try {
      const next = await diffPdfs(leftFile, rightFile)
      setReport(next)
      setSelectedPage(1)
      setVisual(null)
      toast.success(`Compared ${next.comparedPages} page(s): ${next.changedPages} changed`)
    } catch (failure) {
      onError(failure instanceof Error ? failure.message : 'PDF comparison failed.')
    } finally {
      onBusyChange('')
    }
  }

  const loadVisual = async (page: number) => {
    if (!leftFile || !rightFile) return
    onError('')
    onBusyChange(`Rendering visual diff for page ${page}…`)
    try {
      const rendered = await renderPdfDiffPage(leftFile, rightFile, page)
      setSelectedPage(page)
      setVisual(rendered)
    } catch (failure) {
      onError(failure instanceof Error ? failure.message : 'Could not render visual page diff.')
    } finally {
      onBusyChange('')
    }
  }

  const activePageDiff = report?.pages.find(p => p.page === selectedPage) ?? report?.pages[0] ?? null

  return (
    <div className="eco-stack">
      <input
        ref={leftRef}
        type="file"
        accept=".pdf,application/pdf"
        className="sr-only"
        aria-label="Upload baseline left PDF"
        onChange={e => { selectSide('left', e.target.files?.[0]); e.target.value = '' }}
      />
      <input
        ref={rightRef}
        type="file"
        accept=".pdf,application/pdf"
        className="sr-only"
        aria-label="Upload revised right PDF"
        onChange={e => { selectSide('right', e.target.files?.[0]); e.target.value = '' }}
      />

      <div className="eco-two-col">
        <div className="eco-pick-card">
          <h3>Baseline PDF (A)</h3>
          <p>{leftFile ? `${leftFile.name} · ${formatBytes(leftFile.size)}` : 'Select the original or earlier PDF version.'}</p>
          <Button variant="outline" disabled={busy} onClick={() => leftRef.current?.click()}>
            <UploadSimple size={15} />
            {leftFile ? 'Change PDF A' : 'Choose PDF A'}
          </Button>
        </div>
        <div className="eco-pick-card">
          <h3>Revised PDF (B)</h3>
          <p>{rightFile ? `${rightFile.name} · ${formatBytes(rightFile.size)}` : 'Select the updated or compiled PDF version.'}</p>
          <Button variant="outline" disabled={busy} onClick={() => rightRef.current?.click()}>
            <UploadSimple size={15} />
            {rightFile ? 'Change PDF B' : 'Choose PDF B'}
          </Button>
        </div>
      </div>

      <div className="eco-controls-row">
        <Button className="pdf-primary-cta" disabled={busy || !leftFile || !rightFile} onClick={() => void runCompare()}>
          <GitDiff size={16} weight="bold" />
          Compare PDFs
        </Button>
      </div>

      {report && (
        <div className="eco-stack">
          {report.truncated && (
            <p className="pdf-warning-banner" role="status">
              Comparison truncated: checked {report.comparedPages} page(s) (left: {report.leftPages} pages, right: {report.rightPages} pages; max 50 pages and 400 displayed lines per page).
            </p>
          )}
          <div className="review-metrics">
            <div><strong>{report.comparedPages}</strong><span>Compared pages</span></div>
            <div><strong>{report.matchingPages}</strong><span>Unchanged pages</span></div>
            <div><strong>{report.changedPages}</strong><span>Changed pages</span></div>
            <div><strong>+{report.addedLines}</strong><span>Added lines</span></div>
            <div><strong>-{report.removedLines}</strong><span>Removed lines</span></div>
          </div>

          <div className="eco-page-pills" role="navigation" aria-label="Compared PDF pages">
            {report.pages.map(p => (
              <button
                key={p.page}
                type="button"
                aria-current={selectedPage === p.page ? 'page' : undefined}
                className={`eco-page-pill ${p.identical ? 'is-same' : 'is-changed'}`}
                onClick={() => { setSelectedPage(p.page); setVisual(null) }}
              >
                Page {p.page} · {p.identical ? 'Match' : `+${p.added}/-${p.removed}`}
              </button>
            ))}
          </div>

          {activePageDiff && (
            <div className="eco-card-block">
              <div className="pdf-loaded-head">
                <h3>Page {activePageDiff.page} text diff</h3>
                <div className="eco-btn-group">
                  <Badge variant="outline">{activePageDiff.identical ? 'Identical text' : `${activePageDiff.added} added, ${activePageDiff.removed} removed`}</Badge>
                  <Button variant="outline" size="sm" disabled={busy} onClick={() => void loadVisual(activePageDiff.page)}>
                    <Eye size={14} />
                    Render visual pixel diff
                  </Button>
                </div>
              </div>
              <div className="eco-diff-box" aria-label={`Text diff for page ${activePageDiff.page}`}>
                {activePageDiff.lines.length === 0 ? (
                  <p className="eco-diff-note">No selectable text lines on this page.</p>
                ) : activePageDiff.lines.map((line, idx) => (
                  <div key={idx} className={`eco-diff-row is-${line.type}`}>
                    <span className="eco-diff-sign">{line.type === 'add' ? '+' : line.type === 'del' ? '-' : ' '}</span>
                    <code>{line.text}</code>
                  </div>
                ))}
              </div>
            </div>
          )}

          {visual && (
            <div className="eco-visual-diff">
              <div className="pdf-loaded-head">
                <h3>Visual pixel diff · Page {visual.page}</h3>
                <Badge variant="outline">{(visual.pixelDiffRatio * 100).toFixed(2)}% pixel change</Badge>
              </div>
              <div className="eco-visual-grid">
                <figure>
                  <figcaption>Baseline (A)</figcaption>
                  <img src={`data:image/png;base64,${visual.leftPngBase64}`} alt={`Baseline PDF page ${visual.page}`} />
                </figure>
                <figure>
                  <figcaption>Revised (B)</figcaption>
                  <img src={`data:image/png;base64,${visual.rightPngBase64}`} alt={`Revised PDF page ${visual.page}`} />
                </figure>
                <figure>
                  <figcaption>Changed regions</figcaption>
                  <img src={`data:image/png;base64,${visual.diffPngBase64}`} alt={`Visual diff overlay for page ${visual.page}`} />
                </figure>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
