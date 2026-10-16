import { lazy, Suspense, useEffect, useMemo, useRef, useState } from 'react'
import { api, formatBytes } from './api'
import { downloadBibliography } from './bibliographyDownload'
import type { BibliographyReport, Job } from './api'
import { Button } from './components/ui/button'

const BibliographySetupDialog = lazy(() => import('./BibliographySetupDialog').then(module => ({ default: module.BibliographySetupDialog })))

export function BibliographyPanel({ job, dirty, disabled, onImport, onSetup, onInsert }: {
  job: Job; dirty: boolean; disabled: boolean; onImport: (file: File) => Promise<boolean>
  onSetup: (revision: number, hash: string) => Promise<boolean>; onInsert: (key: string, revision: number) => void
}) {
  const [attempt, setAttempt] = useState(0), [search, setSearch] = useState(''), [error, setError] = useState('')
  const [importing, setImporting] = useState(false), [setupOpen, setSetupOpen] = useState(false)
  const [downloading, setDownloading] = useState(false)
  const file = useRef<HTMLInputElement>(null)
  const request = useMemo(() => ({ id: job.id, revision: job.revision, attempt }), [job.id, job.revision, attempt])
  const [loaded, setLoaded] = useState<{ request: object; report: BibliographyReport | null; error: string } | null>(null)
  const current = loaded?.request === request ? loaded : null, report = current?.report
  const matches = report?.entries.filter(entry => `${entry.key} ${entry.title} ${entry.author} ${entry.year}`.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase())) ?? []
  const canChange = !disabled && !dirty && !importing && report?.revision === job.revision
  useEffect(() => {
    const controller = new AbortController()
    void api<BibliographyReport>(`/jobs/${request.id}/bibliography`, { signal: controller.signal })
      .then(report => { if (!controller.signal.aborted) setLoaded({ request, report, error: '' }) })
      .catch((error: Error) => { if (!controller.signal.aborted) setLoaded({ request, report: null, error: error.message }) })
    return () => controller.abort()
  }, [request])
  const upload = async (selected: File | undefined) => {
    if (!selected || !canChange) return
    if (!selected.name.toLowerCase().endsWith('.bib') || selected.size === 0 || selected.size > 1024 * 1024) { setError('Choose a non-empty UTF-8 .bib file up to 1 MiB.'); return }
    setImporting(true); setError('')
    try { if (!(await onImport(selected))) setError('Save your source and finish any text composition before importing again.') }
    catch (error) { setError(error instanceof Error ? error.message : 'Could not import this bibliography.') }
    finally { setImporting(false) }
  }
  return <section className="ui-bibliography-panel" aria-label="Reference library">
    <p>Import a UTF-8 .bib file, up to 1 MiB and 2,000 entries. Each import creates a saved version with its matching bibliography; your source stays unchanged.</p>
    {!current && <p role="status">Loading bibliography…</p>}
    {current?.error && <p role="alert">Could not load bibliography: {current.error}</p>}
    <div className="ui-bibliography-actions"><input ref={file} type="file" className="sr-only" accept=".bib,application/x-bibtex,text/plain" aria-label="Import BibTeX bibliography" disabled={!canChange} onChange={event => { const selected = event.currentTarget.files?.[0]; event.currentTarget.value = ''; void upload(selected) }} />
      <Button variant="outline" disabled={!canChange} onClick={() => file.current?.click()}>{importing ? 'Importing…' : report?.filename ? 'Replace bibliography' : 'Import bibliography'}</Button>
      <Button variant="ghost" disabled={importing} onClick={() => { setError(''); setAttempt(value => value + 1) }}>Reload bibliography</Button>
      {!!report?.filename && <Button variant="ghost" disabled={disabled || downloading} onClick={() => { setError(''); setDownloading(true); void downloadBibliography(`/jobs/${job.id}/bibliography/file`, 'references.bib').catch(error => setError(error instanceof Error ? error.message : 'Could not download bibliography.')).finally(() => setDownloading(false)) }}>Download bibliography</Button>}
    </div>
    {error && <p role="alert">{error}</p>}
    {dirty && <p>Save source changes before importing or adding a bibliography section. Save to refresh the unknown-key check.</p>}
    {report && <>
      <p>{report.filename ? `${report.filename} · ${formatBytes(report.bytes)} · ${report.entries.length} keys` : 'No bibliography attached.'} Saved source revision {report.revision}.</p>
      {report.revision !== job.revision && <p role="alert">The saved revision changed. Reload the document before using this library.</p>}
      {!!report.unknownKeys.length && <p role="status">Unknown citation keys: {report.unknownKeys.join(', ')}. Import matching entries or correct the keys in source.</p>}
      {!!report.warnings.length && <details><summary>Bibliography items to review</summary><ul>{report.warnings.map((warning, index) => <li key={index}>{warning}</li>)}</ul></details>}
      {!!report.filename && <><Button variant="outline" disabled={!canChange} onClick={() => setSetupOpen(true)}>Preview bibliography setup</Button>
        <p>Place the source cursor in your document body, then insert a key. Insertions are unsaved edits and can be undone.</p>
        <label className="ui-bibliography-search">Search citation keys<input maxLength={200} value={search} onChange={event => setSearch(event.target.value)} /></label>
        <ul className="ui-bibliography-entries">{matches.slice(0, 50).map(entry => <li key={entry.key}><div><code>{entry.key}</code><strong>{entry.title || 'Title not provided'}</strong><span>{[entry.author, entry.year, entry.type].filter(Boolean).join(' · ')}</span></div>
          <Button variant="outline" disabled={disabled || report.revision !== job.revision} aria-label={`Insert citation ${entry.key}`} onClick={() => onInsert(entry.key, report.revision)}>Insert citation</Button></li>)}</ul>
        {matches.length === 0 && <p>No matching citation keys.</p>}{matches.length > 50 && <p>Showing the first 50 of {matches.length} matches. Narrow your search to find another key.</p>}
      </>}
    </>}
    {setupOpen && <Suspense fallback={<p role="status">Loading bibliography setup…</p>}><BibliographySetupDialog job={job} disabled={disabled || dirty} onClose={() => setSetupOpen(false)} onApply={onSetup} /></Suspense>}
  </section>
}
