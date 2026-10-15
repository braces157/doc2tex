import { ArrowUpRight, CheckCircle, Info, Warning } from '@phosphor-icons/react'
import { Badge } from '@/components/ui/badge'
import type { ConversionReview as ReviewData, Job } from '@/api'

export function ConversionReview({ review, job }: { review: ReviewData | null; job: Job | null }) {
  if (!job) return <div className="review-empty"><div className="review-empty-icon"><CheckCircle size={32} weight="light" /></div><h3>Know what made the journey.</h3><p>After conversion, see the document features we detected and the details worth reviewing.</p><div className="review-checklist"><span>Document structure</span><span>Equations & images</span><span>Fonts & compiler warnings</span></div></div>
  if (!review) return <div className="review-empty" aria-busy="true">Inspecting your document…</div>
  const warnings = review.findings.filter(finding => finding.severity === 'warning').length + review.compilerWarnings.length
  return <div className="review-content">
    <div className="review-summary"><div><span className="ui-eyebrow">CONVERSION REVIEW</span><h3>{warnings ? 'A few details to check.' : 'Ready for your final read.'}</h3></div><Badge variant="outline" className={warnings ? 'review-warning-badge' : 'review-clear-badge'}>{warnings ? `${warnings} to review` : 'No flags detected'}</Badge></div>
    <p className="review-intro">Detected in your Word document</p><div className="review-metrics">{Object.entries(review.elements).map(([name, count]) => <div key={name}><strong>{count.toString().padStart(2,'0')}</strong><span>{name}</span></div>)}</div>
    {review.compilerWarnings.map((warning,index) => <div className="review-finding compiler-warning" key={`compiler-${index}`}><Warning size={19} /><div><strong>PDF needs review</strong><p>{warning}</p></div></div>)}
    {review.findings.map(finding => <div className={`review-finding ${finding.severity}`} key={finding.code}>{finding.severity === 'warning' ? <Warning size={18} /> : <Info size={18} />}<div><strong>{finding.title}</strong><p>{finding.detail}</p></div></div>)}
    {!review.findings.length && !review.compilerWarnings.length && <div className="review-finding info"><CheckCircle size={18} /><div><strong>No common conversion risks detected</strong><p>Read through your equations, tables, and references before publishing.</p></div></div>}
    <div className="review-scope"><Info size={15} /><p>{review.scope}{job.revision > 0 && ' You have edited this source; counts still describe the original Word document.'}</p></div>
    {['READY','FAILED'].includes(job.previewStatus) && <a className="review-log-link" href={`/api/jobs/${job.id}/preview/log`} target="_blank" rel="noreferrer">Open compiler log <ArrowUpRight size={13} /></a>}
  </div>
}
