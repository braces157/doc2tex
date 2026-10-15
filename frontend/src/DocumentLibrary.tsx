import { useEffect, useMemo, useState } from 'react'
import { api } from './api'
import type { Job, LibraryPage } from './api'
import { Button } from './components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from './components/ui/dialog'

export function DocumentLibrary({ open, onOpenChange, onSelect, disabled }: {
  open: boolean; onOpenChange: (open: boolean) => void; onSelect: (job: Job) => void; disabled: boolean
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

  useEffect(() => {
    if (!open) return
    const controller = new AbortController()
    const params = new URLSearchParams({ ...filters, page: String(filters.page), size: '20' })
    void api<LibraryPage>(`/library?${params}`, { signal: controller.signal })
      .then(result => { if (!controller.signal.aborted) setLoaded({ request, result, error: '' }) })
      .catch((e: Error) => { if (!controller.signal.aborted) setLoaded({ request, result: null, error: e.message }) })
    return () => controller.abort()
  }, [open, filters, request])

  return <Dialog open={open} onOpenChange={onOpenChange}>
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
            <button disabled={disabled} onClick={() => { onOpenChange(false); onSelect(job) }}>
              <strong>{job.filename}</strong>
              <span><time dateTime={job.createdAt}>{new Date(job.createdAt).toLocaleString()}</time> · {job.template} · {job.status.toLowerCase()}</span>
            </button>
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
}
