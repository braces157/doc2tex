import { useEffect, useMemo, useState } from 'react'
import { api } from './api'
import type { BibliographyPlan, Job } from './api'
import { Button } from './components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from './components/ui/dialog'

export function BibliographySetupDialog({ job, disabled, onClose, onApply }: {
  job: Job; disabled: boolean; onClose: () => void; onApply: (revision: number, hash: string) => Promise<boolean>
}) {
  const [attempt, setAttempt] = useState(0), [applying, setApplying] = useState(false), [error, setError] = useState('')
  const request = useMemo(() => ({ id: job.id, revision: job.revision, attempt }), [job.id, job.revision, attempt])
  const [loaded, setLoaded] = useState<{ request: object; plan: BibliographyPlan | null; error: string } | null>(null)
  const current = loaded?.request === request ? loaded : null, plan = current?.plan
  useEffect(() => {
    const controller = new AbortController()
    void api<BibliographyPlan>(`/jobs/${request.id}/bibliography/setup`, { signal: controller.signal })
      .then(plan => { if (!controller.signal.aborted) setLoaded({ request, plan, error: '' }) })
      .catch((error: Error) => { if (!controller.signal.aborted) setLoaded({ request, plan: null, error: error.message }) })
    return () => controller.abort()
  }, [request])
  const apply = async () => {
    if (!plan?.changes.length || disabled || applying || plan.revision !== job.revision) return
    setApplying(true); setError('')
    try { if (await onApply(plan.revision, plan.sourceHash)) onClose() }
    catch (error) { setError(error instanceof Error ? error.message : 'Could not add bibliography setup.') }
    finally { setApplying(false) }
  }
  return <Dialog open onOpenChange={value => { if (!value && !applying) onClose() }}><DialogContent className="caption-repairs-dialog"><DialogHeader><DialogTitle>Preview bibliography setup</DialogTitle>
    <DialogDescription>Review the exact source changes for a plain BibTeX bibliography. Applying creates a saved version with the same attachment. Tectonic builds it automatically; external TeX projects use XeLaTeX plus BibTeX.</DialogDescription></DialogHeader>
    {!current && <p role="status">Preparing bibliography setup…</p>}{current?.error && <p role="alert">{current.error}</p>}
    {plan && <><p>Saved source revision {plan.revision}.</p><ul>{plan.warnings.map((warning, index) => <li key={index}>{warning}</li>)}</ul>
      {!plan.changes.length && <p>No safe setup change is available. Review existing bibliography commands or custom document structure manually.</p>}
      <div className="caption-repair-proposals">{plan.changes.map((change, index) => <article key={index}><strong>{change.title} · line {change.line}</strong>
        <div className="caption-source-diff"><div><strong>Before</strong><pre aria-label={`Before bibliography change ${index + 1}`}>{change.before}</pre></div><div><strong>After</strong><pre aria-label={`After bibliography change ${index + 1}`}>{change.after}</pre></div></div>
      </article>)}</div></>}
    {error && <p role="alert">{error}</p>}
    <div className="caption-repair-actions"><Button variant="ghost" disabled={applying} onClick={() => { setError(''); setAttempt(value => value + 1) }}>Preview again</Button><Button variant="outline" disabled={applying} onClick={onClose}>Cancel</Button>
      <Button disabled={!plan?.changes.length || disabled || applying || plan.revision !== job.revision} onClick={() => void apply()}>{applying ? 'Applying…' : 'Apply bibliography setup'}</Button></div>
  </DialogContent></Dialog>
}
