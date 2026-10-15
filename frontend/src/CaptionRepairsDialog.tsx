import { useEffect, useMemo, useState } from 'react'
import { api } from './api'
import type { CaptionRepairPlan, Job } from './api'
import { Button } from './components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from './components/ui/dialog'

export function CaptionRepairsDialog({ open, onOpenChange, job, disabled, onApply }: {
  open: boolean; onOpenChange: (value: boolean) => void; job: Job | null; disabled: boolean
  onApply: (revision: number, sourceHash: string, ids: string[]) => Promise<boolean>
}) {
  const [attempt, setAttempt] = useState(0)
  const request = useMemo(() => ({ open, id: job?.id, revision: job?.revision, attempt }), [open, job?.id, job?.revision, attempt])
  const [loaded, setLoaded] = useState<{ request: object; plan: CaptionRepairPlan | null; error: string } | null>(null)
  const [selection, setSelection] = useState<{ request: object; ids: string[] } | null>(null)
  const [applying, setApplying] = useState(false)
  const [applyError, setApplyError] = useState('')
  const current = loaded?.request === request ? loaded : null, plan = current?.plan
  const ids = selection?.request === request ? selection.ids : plan?.proposals.map(proposal => proposal.id) ?? []
  const stale = Boolean(plan && plan.revision !== job?.revision)
  useEffect(() => {
    if (!request.open || !request.id) return
    const controller = new AbortController()
    void api<CaptionRepairPlan>(`/jobs/${request.id}/caption-repairs`, { signal: controller.signal })
      .then(plan => { if (!controller.signal.aborted) setLoaded({ request, plan, error: '' }) })
      .catch((error: Error) => { if (!controller.signal.aborted) setLoaded({ request, plan: null, error: error.message }) })
    return () => controller.abort()
  }, [request])
  const apply = async () => {
    if (!plan || disabled || applying || stale || !ids.length) return
    setApplying(true); setApplyError('')
    try { if (await onApply(plan.revision, plan.sourceHash, ids)) onOpenChange(false) }
    catch (error) { setApplyError(error instanceof Error ? error.message : 'Could not apply caption repairs.') }
    finally { setApplying(false) }
  }
  return <Dialog open={open} onOpenChange={value => { if (!applying) { setApplyError(''); onOpenChange(value) } }}>
    <DialogContent className="caption-repairs-dialog"><DialogHeader><DialogTitle>Preview caption repairs</DialogTitle>
      <DialogDescription>Review the exact source changes. LaTeX already numbers these captions; removing the literal prefix avoids a second number. Applying creates a new saved version that you can restore from Saved versions.</DialogDescription></DialogHeader>
      {!current && <p role="status">Preparing caption proposals…</p>}
      {current?.error && <p role="alert">Could not prepare repairs: {current.error}</p>}
      {plan && <><p>Saved source revision {plan.revision}. Ordinary prose and unsupported references are unchanged.</p>
        {stale && <p role="alert">The saved source changed. Reopen the current document before applying repairs.</p>}
        {!!plan.warnings.length && <details><summary>Items requiring manual review</summary><ul>{plan.warnings.map((warning, index) => <li key={index}>{warning}</li>)}</ul></details>}
        {!plan.proposals.length && <p>No safe caption-numbering repairs were found. Review custom captions, optional short text and ambiguous ownership manually.</p>}
        <div className="caption-repair-proposals">{plan.proposals.map(proposal => <article key={proposal.id}>
          <label><input type="checkbox" checked={ids.includes(proposal.id)} disabled={disabled || applying || stale} onChange={event => setSelection({ request, ids: event.target.checked ? [...ids, proposal.id] : ids.filter(id => id !== proposal.id) })} />{proposal.title} · line {proposal.line}</label>
          <div className="caption-source-diff"><div><strong>Before</strong><pre aria-label={`Source before repair at line ${proposal.line}`}>{proposal.before}</pre></div><div><strong>After</strong><pre aria-label={`Source after repair at line ${proposal.line}`}>{proposal.after}</pre></div></div>
        </article>)}</div>
      </>}
      {applyError && <p role="alert">{applyError}</p>}
      <div className="caption-repair-actions"><Button variant="ghost" disabled={applying} onClick={() => { setApplyError(''); setAttempt(value => value + 1) }}>Preview again</Button>
        <Button variant="outline" disabled={applying} onClick={() => onOpenChange(false)}>Cancel</Button>
        <Button disabled={!plan || !ids.length || stale || disabled || applying} onClick={() => void apply()}>{applying ? 'Applying…' : 'Apply selected repairs'}</Button></div>
    </DialogContent>
  </Dialog>
}
