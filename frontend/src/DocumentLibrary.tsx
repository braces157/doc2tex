import { useEffect, useMemo, useState } from 'react'
import { api, jobTitle } from './api'
import type { Job, LibraryPage } from './api'
import { Button } from './components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from './components/ui/dialog'
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription,
  AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from './components/ui/alert-dialog'

export function DocumentLibrary({ open, onOpenChange, onSelect, onRenamed, onDeleted, disabled }: {
  open: boolean; onOpenChange: (open: boolean) => void; onSelect: (job: Job) => void; disabled: boolean
  onRenamed: (job: Job) => void; onDeleted: (job: Job) => void
}) {
  const [input, setInput] = useState('')
  const [filters, setFilters] = useState({ query: '', status: '', sort: 'newest', page: 0 })
  const [attempt, setAttempt] = useState(0)
  const request = useMemo(() => ({ open, filters, attempt }), [open, filters, attempt])
  const [loaded, setLoaded] = useState<{ request: object; result: LibraryPage | null; error: string } | null>(null)
  const current = loaded?.request === request ? loaded : null
  const result = current?.result ?? null
  const error = current?.error ?? ''
  const loading = !current
  const [action, setAction] = useState<{ kind: 'rename' | 'delete'; job: Job } | null>(null)
  const [name, setName] = useState('')
  const [working, setWorking] = useState(false)
  const [mutationError, setMutationError] = useState('')
  const manage = (kind: 'rename' | 'delete', job: Job) => { setMutationError(''); setName(job.displayName ?? ''); setAction({ kind, job }) }
  const closeAction = () => { if (!working) setAction(null) }
  const mutate = async () => {
    if (!action || working) return
    setWorking(true); setMutationError('')
    try {
      if (action.kind === 'rename') {
        const next = await api<Job>(`/jobs/${action.job.id}`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ displayName: name }) })
        onRenamed(next)
      } else {
        await api<void>(`/jobs/${action.job.id}`, { method: 'DELETE' })
        onDeleted(action.job)
      }
      setAction(null)
    } catch (e) { setMutationError(e instanceof Error ? e.message : 'The document could not be updated. Try again.') }
    finally { setWorking(false); setAttempt(value => value + 1) }
  }

  useEffect(() => {
    if (!open) return
    const controller = new AbortController()
    const params = new URLSearchParams({ ...filters, page: String(filters.page), size: '20' })
    void api<LibraryPage>(`/library?${params}`, { signal: controller.signal })
      .then(result => { if (!controller.signal.aborted) setLoaded({ request, result, error: '' }) })
      .catch((e: Error) => { if (!controller.signal.aborted) setLoaded({ request, result: null, error: e.message }) })
    return () => controller.abort()
  }, [open, filters, request])

  return <><Dialog open={open} onOpenChange={value => { if (!working) { onOpenChange(value); if (!value) setAction(null) } }}>
    <DialogContent className="ui-library-dialog">
      <DialogHeader><DialogTitle>All documents</DialogTitle><DialogDescription>Find any conversion saved on this machine.</DialogDescription></DialogHeader>
      <form className="ui-library-controls" onSubmit={event => { event.preventDefault(); setFilters(previous => ({ ...previous, query: input, page: 0 })) }}>
        <label className="ui-library-search">Filename<input type="search" value={input} maxLength={200} onChange={event => setInput(event.target.value)} placeholder="Search filenames" /></label>
        <Button type="submit" variant="outline">Search</Button>
        <label>Status<select aria-label="Status" value={filters.status} onChange={event => setFilters(previous => ({ ...previous, status: event.target.value, page: 0 }))}>
          <option value="">All statuses</option><option value="COMPLETED">Completed</option><option value="FAILED">Failed</option><option value="QUEUED">Queued</option><option value="CONVERTING">Converting</option>
        </select></label>
        <label>Sort<select aria-label="Sort" value={filters.sort} onChange={event => setFilters(previous => ({ ...previous, sort: event.target.value, page: 0 }))}>
          <option value="newest">Newest first</option><option value="oldest">Oldest first</option>
        </select></label>
      </form>
      <div className="ui-library-results" aria-busy={loading} aria-label="Document results">
        {loading && <p role="status">Loading documents…</p>}
        {error && <div role="alert"><p>{error}</p><Button variant="outline" onClick={() => setAttempt(value => value + 1)}>Retry library</Button></div>}
        {!loading && result && <>
          <p role="status">{result.total} {result.total === 1 ? 'document' : 'documents'} found</p>
          {result.items.length === 0 && <p>{filters.page > 0 ? 'No documents on this page. Go back to the previous page.' : 'No matching documents. Try another filename or status.'}</p>}
          <ul className="ui-library-list">{result.items.map(job => <li key={job.id}>
            <button aria-label={`Open ${jobTitle(job)}`} disabled={disabled || working || job.deletionPending} onClick={() => { onOpenChange(false); onSelect(job) }}>
              <strong>{jobTitle(job)}</strong>
              {job.displayName && <span>Original: {job.filename}</span>}
              <span><time dateTime={job.createdAt}>{new Date(job.createdAt).toLocaleString()}</time> · {job.template} · {job.status.toLowerCase()}</span>
              {job.deletionPending && <span>Deletion incomplete. Retry Delete to finish cleanup.</span>}
            </button>
            <div className="ui-library-row-actions">
              <Button variant="ghost" disabled={disabled || working || job.deletionPending} aria-label={`Rename ${jobTitle(job)}`} onClick={() => manage('rename', job)}>Rename</Button>
              <Button variant="ghost" disabled={disabled || working} aria-label={`${job.deletionPending ? 'Retry delete' : 'Delete'} ${jobTitle(job)}`} onClick={() => manage('delete', job)}>{job.deletionPending ? 'Retry delete' : 'Delete'}</Button>
            </div>
          </li>)}</ul>
        </>}
      </div>
      <nav className="ui-library-pages" aria-label="Library pages">
        <Button variant="outline" disabled={loading || filters.page === 0} onClick={() => setFilters(previous => ({ ...previous, page: previous.page - 1 }))}>Previous page</Button>
        <span>Page {filters.page + 1}{result ? ` of ${Math.max(1, Math.ceil(result.total / result.size))}` : ''}</span>
        <Button variant="outline" disabled={loading || !result || (filters.page + 1) * result.size >= result.total} onClick={() => setFilters(previous => ({ ...previous, page: previous.page + 1 }))}>Next page</Button>
      </nav>
    </DialogContent>
  </Dialog>
    <Dialog open={action?.kind === 'rename'} onOpenChange={value => { if (!value) closeAction() }}>
      <DialogContent><DialogHeader><DialogTitle>Rename document</DialogTitle><DialogDescription>The original filename stays {action?.job.filename}. Clear the display name to use it again.</DialogDescription></DialogHeader>
        <form className="ui-document-rename" onSubmit={event => { event.preventDefault(); void mutate() }}>
          <label>Display name<input value={name} onChange={event => setName(event.target.value)} maxLength={200} disabled={working} autoFocus /></label>
          {mutationError && <p role="alert">{mutationError}</p>}
          <div><Button variant="outline" type="button" onClick={closeAction} disabled={working}>Cancel</Button><Button type="submit" disabled={working}>{working ? 'Saving…' : 'Save name'}</Button></div>
        </form>
      </DialogContent>
    </Dialog>
    <AlertDialog open={action?.kind === 'delete'} onOpenChange={value => { if (!value) closeAction() }}>
      <AlertDialogContent><AlertDialogHeader><AlertDialogTitle>Delete {action ? jobTitle(action.job) : 'document'}?</AlertDialogTitle>
        <AlertDialogDescription>This permanently removes the original Word document, source, saved versions, media, and PDF previews from this machine. Unsaved edits in this document will be discarded in this tab. Other open tabs should download any unsaved text before leaving.</AlertDialogDescription>
      </AlertDialogHeader>
        {mutationError && <p role="alert">{mutationError}</p>}
        <AlertDialogFooter><AlertDialogCancel disabled={working} onClick={closeAction}>Cancel</AlertDialogCancel><AlertDialogAction disabled={working} onClick={event => { event.preventDefault(); void mutate() }}>{working ? 'Deleting…' : 'Delete document'}</AlertDialogAction></AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  </>
}
