import { ArrowUpRight, CheckCircle, Info, Warning } from '@phosphor-icons/react'
import { Badge } from '@/components/ui/badge'
import type { ConversionReview as ReviewData, Job } from '@/api'

interface Props {
  review: ReviewData | null; job: Job | null; dirty: boolean; canDecide: boolean; saving: boolean; error: string
  onRetry: () => void; onDecide: (id: string, status: 'reviewed' | 'needs-work' | 'unreviewed') => void
  onCompare: (id: string) => void; onNavigate: (line: number, revision: number) => void
  onCaptionRepairs: () => void
}

export function ConversionReview({ review, job, dirty, canDecide, saving, error, onRetry, onDecide, onCompare, onNavigate, onCaptionRepairs }: Props) {
  if (!job) return <div className="review-empty"><div className="review-empty-icon"><CheckCircle size={32} weight="light" /></div><h3>Know what made the journey.</h3><p>After conversion, see the document features we detected and the details worth reviewing.</p><div className="review-checklist"><span>Document structure</span><span>Equations & images</span><span>Fonts & compiler warnings</span></div></div>
  if (!review) return error ? <div className="review-empty" role="alert"><p>Could not load conversion review: {error}</p><button onClick={onRetry}>Retry conversion review</button></div> : <div className="review-empty" aria-busy="true">Inspecting your document…</div>
  const warnings = review.findings.filter(finding => finding.severity === 'warning').length + review.compilerWarnings.length
  const items = review.items ?? [], decisions = review.decisions ?? []
  return <div className="review-content">
    <div className="review-summary"><div><span className="ui-eyebrow">CONVERSION REVIEW</span><h3>{warnings ? 'A few details to check.' : 'Ready for your final read.'}</h3></div><Badge variant="outline" className={warnings ? 'review-warning-badge' : 'review-clear-badge'}>{warnings ? `${warnings} to review` : 'No flags detected'}</Badge></div>
    <p className="review-intro">Detected in your Word document</p><div className="review-metrics">{Object.entries(review.elements).map(([name, count]) => <div key={name}><strong>{count.toString().padStart(2,'0')}</strong><span>{name}</span></div>)}</div>
    <button className="secondary-button" disabled={!canDecide || saving} onClick={onCaptionRepairs}>Preview caption repairs</button>
    {error && <p role="alert">Could not update review decisions: {error} <button onClick={onRetry}>Reload conversion review</button></p>}
    {review.decisionError && <p role="alert">{review.decisionError}</p>}
    {review.originalTruncated && <p role="status">Original-text inspection was limited. Some excerpts or findings may be missing. Source links are unavailable where evidence cannot be verified.</p>}
    {review.revision !== undefined && <p className="review-intro">Saved source revision {review.revision}. Decisions are separate from detected risks. Unchanged mapped evidence can carry to later revisions; unmapped or changed evidence requires review again.</p>}
    {dirty && <p role="status">You have unsaved edits. Decisions describe saved source; save your edits to re-evaluate evidence before recording decisions or following source links.</p>}
    {review.revision !== undefined && review.revision !== job.revision && <p role="status">This review describes a different saved revision. Reopen the current source and reload the review.</p>}
    {!!items.length && <div className="guided-review-list" aria-label="Original findings">
      <p>{decisions.filter(decision => decision.applicable && decision.status === 'reviewed').length} of {items.length} occurrences marked reviewed for saved source. Detected warnings remain below.</p>
      {items.map(item => {
        const decision = decisions.find(decision => decision.findingId === item.id)
        const applicable = decision?.applicable && !dirty
        return <article className="guided-review-item" key={item.id} aria-label={`${item.title}, ${item.location}`}>
          <h4>{item.title}</h4><p className="original-location">{item.part} · {item.location}</p>
          <blockquote>{item.excerpt}</blockquote>{item.excerptTruncated && <p>Original excerpt was shortened.</p>}
          <p>{item.mapping.status === 'unavailable' && <strong>Source location unavailable. </strong>}{item.mapping.reason}</p>
          <p className="review-decision-status">{decision ? applicable ? `${decision.status === 'reviewed' ? 'Reviewed' : 'Needs work'}${decision.carried ? ` · carried from revision ${decision.revision}` : ` · revision ${decision.revision}`}` : `Review again · previous decision: ${decision.status} at revision ${decision.revision}` : 'Not reviewed'}</p>
          {decision && <p>{decision.reason}</p>}
          <div className="guided-review-actions">
            <button onClick={() => onCompare(item.id)}>Compare original text</button>
            {item.mapping.status === 'matched' && item.mapping.line !== null && <button disabled={!canDecide} onClick={() => { onCompare(item.id); onNavigate(item.mapping.line!, review.revision!) }}>Go to source line {item.mapping.line}</button>}
            <button disabled={!canDecide || saving} onClick={() => onDecide(item.id, 'reviewed')}>Mark reviewed</button>
            <button disabled={!canDecide || saving} onClick={() => onDecide(item.id, 'needs-work')}>Needs work</button>
            {decision && <button disabled={!canDecide || saving} onClick={() => onDecide(item.id, 'unreviewed')}>Clear decision</button>}
          </div>
        </article>
      })}
    </div>}
    {review.compilerWarnings.map((warning,index) => <div className="review-finding compiler-warning" key={`compiler-${index}`}><Warning size={19} /><div><strong>PDF needs review</strong><p>{warning}</p></div></div>)}
    {review.findings.map(finding => <div className={`review-finding ${finding.severity}`} key={finding.code}>{finding.severity === 'warning' ? <Warning size={18} /> : <Info size={18} />}<div><strong>{finding.title}</strong><p>{finding.detail}</p></div></div>)}
    {!review.findings.length && !review.compilerWarnings.length && <div className="review-finding info"><CheckCircle size={18} /><div><strong>No common conversion risks detected</strong><p>Read through your equations, tables, and references before publishing.</p></div></div>}
    <div className="review-scope"><Info size={15} /><p>{review.scope}{job.revision > 0 && ' You have edited this source; counts still describe the original Word document.'}</p></div>
    {['READY','FAILED'].includes(job.previewStatus) && <a className="review-log-link" href={`/api/jobs/${job.id}/preview/log`} target="_blank" rel="noreferrer">Open compiler log <ArrowUpRight size={13} /></a>}
  </div>
}
