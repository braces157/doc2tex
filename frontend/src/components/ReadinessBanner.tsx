import { ArrowDown, ArrowRight, CheckCircle, Clock, Info, Warning, WarningCircle } from '@phosphor-icons/react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import type { ConversionReview, Job } from '@/api'

interface Props {
  job: Job | null
  review: ConversionReview | null
  dirty: boolean
  compiling: boolean
  view: 'source' | 'pdf' | 'review'
  warningCount: number
  readyForActions: boolean
  previewAvailable: boolean
  onBuild: () => void
  onDownloadPdf: () => void
  onOpenView: (view: 'source' | 'pdf' | 'review') => void
}

export function ReadinessBanner({
  job,
  review,
  dirty,
  compiling,
  view,
  warningCount,
  readyForActions,
  previewAvailable,
  onBuild,
  onDownloadPdf,
  onOpenView,
}: Props) {
  if (!job || job.status !== 'COMPLETED') return null

  const readiness = review?.readiness
  if (!readiness) {
    if (warningCount > 0 && view !== 'review') {
      return (
        <button className="ui-review-prompt is-top" onClick={() => onOpenView('review')}>
          <WarningCircle size={16} weight="fill" />
          <span>{warningCount} {warningCount === 1 ? 'item needs' : 'items need'} review before you publish.</span>
          <span>Open review <ArrowRight size={13} /></span>
        </button>
      )
    }
    return null
  }

  if (dirty) {
    return (
      <section className="ui-readiness is-stale" aria-label="Document readiness">
        <div className="ui-readiness-head">
          <div className="ui-readiness-title">
            <Info size={18} />
            <div>
              <h3>Source edited — PDF check out of date</h3>
              <p>You edited the LaTeX source. Save and rebuild the PDF to re-check readiness.</p>
            </div>
          </div>
          <div className="ui-readiness-actions">
            {previewAvailable && (
              <Button variant="outline" size="sm" disabled={!readyForActions} onClick={onBuild}>
                Build PDF
              </Button>
            )}
          </div>
        </div>
      </section>
    )
  }

  if (compiling) {
    return (
      <section className="ui-readiness is-busy" aria-label="Document readiness" aria-busy="true">
        <div className="ui-readiness-head">
          <div className="ui-readiness-title">
            <Clock size={18} />
            <div>
              <h3>Preparing PDF…</h3>
              <p>Building and verifying your PDF…</p>
            </div>
          </div>
        </div>
      </section>
    )
  }

  if (readiness.revision !== job.revision) return null

  const toneClass =
    readiness.status === 'ready'
      ? 'is-ready'
      : readiness.status === 'ready-with-notes'
        ? 'is-notes'
        : 'is-attention'

  const statusIcon =
    readiness.status === 'ready' ? (
      <CheckCircle size={18} weight="fill" />
    ) : readiness.status === 'ready-with-notes' ? (
      <WarningCircle size={18} weight="fill" />
    ) : (
      <Warning size={18} weight="fill" />
    )

  const automaticItems =
    readiness.handledAutomatically.length > 0
      ? readiness.handledAutomatically
      : readiness.autoFixes.map(fix => fix.detail)

  return (
    <section className={`ui-readiness ${toneClass}`} aria-label="Document readiness">
      <div className="ui-readiness-head">
        <div className="ui-readiness-title">
          {statusIcon}
          <div>
            <div className="ui-readiness-headline-row">
              <h3>{readiness.headline}</h3>
              {readiness.pdfVerified && readiness.pageCount !== null && (
                <Badge variant="outline" className="ui-readiness-badge">
                  Verified · {readiness.pageCount} {readiness.pageCount === 1 ? 'page' : 'pages'}
                </Badge>
              )}
            </div>
            <p>{readiness.summary}</p>
          </div>
        </div>
        <div className="ui-readiness-actions">
          {job.previewStatus === 'READY' ? (
            <>
              <Button size="sm" className="ui-readiness-primary" onClick={onDownloadPdf}>
                <ArrowDown size={14} weight="bold" />
                Download PDF
              </Button>
              {view !== 'pdf' && (
                <Button variant="outline" size="sm" onClick={() => onOpenView('pdf')}>
                  View PDF
                </Button>
              )}
            </>
          ) : (
            previewAvailable && (
              <Button size="sm" className="ui-readiness-primary" disabled={!readyForActions} onClick={onBuild}>
                Build PDF
              </Button>
            )
          )}
          {view !== 'review' && (
            <Button variant="ghost" size="sm" onClick={() => onOpenView('review')}>
              Open Review
            </Button>
          )}
          {view !== 'source' && (
            <Button variant="ghost" size="sm" onClick={() => onOpenView('source')}>
              Edit LaTeX
            </Button>
          )}
        </div>
      </div>

      {readiness.actions.length > 0 && (
        <ul className="ui-readiness-list">
          {readiness.actions.map(item => (
            <li key={item.code} className={`ui-readiness-item is-${item.severity}`}>
              <strong>{item.title}</strong>
              <span>{item.guidance}</span>
            </li>
          ))}
        </ul>
      )}

      {automaticItems.length > 0 && (
        <details className="ui-readiness-details">
          <summary>What was handled automatically ({automaticItems.length})</summary>
          <ul>
            {automaticItems.map((detail, index) => (
              <li key={index}>{detail}</li>
            ))}
          </ul>
        </details>
      )}
    </section>
  )
}
