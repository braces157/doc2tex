import { useEffect, useState } from 'react'
import type { CompilerReport, Job } from '@/api'
import { api } from '@/api'

export function CompilerDiagnosticsPanel({ job, dirty, canNavigate, onNavigate }: { job: Job; dirty: boolean; canNavigate: boolean; onNavigate: (line: number, revision: number) => void }) {
  const [result, setResult] = useState<{ key: string; report: CompilerReport | null; error: string } | null>(null)
  const [retry, setRetry] = useState(0)
  const { id, revision, previewStatus } = job
  const key = `${id}|${revision}|${previewStatus}|${retry}`
  useEffect(() => {
    const controller = new AbortController()
    if (previewStatus === 'QUEUED' || previewStatus === 'COMPILING') return
    if (previewStatus === 'NOT_REQUESTED' && revision === 0) return
    void api<CompilerReport>(`/jobs/${id}/diagnostics`, { signal: controller.signal }).then(report => {
      if (!controller.signal.aborted) setResult({ key, report, error: '' })
    }).catch((error: Error) => { if (!controller.signal.aborted) setResult({ key, report: null, error: error.message }) })
    return () => controller.abort()
  }, [id, revision, previewStatus, key])
  const current = result?.key === key ? result : null
  const report = current?.report ?? null
  const error = current?.error ?? ''
  const working = previewStatus === 'QUEUED' || previewStatus === 'COMPILING'
  const loading = !current && !working
  const stale = Boolean(report && (report.revision === null || report.revision !== revision || dirty))
  if (previewStatus === 'NOT_REQUESTED' && revision === 0 || !working && !loading && !error && (!report || report.status === 'NOT_REQUESTED' && !report.items.length)) return null
  return <section className="compiler-diagnostics" aria-label="Compiler diagnostics" aria-busy={working || loading}>
    <h3>{working ? 'Building PDF…' : loading ? 'Loading compiler diagnostics…' : error ? 'Compiler diagnostics unavailable' : report?.status === 'NOT_REQUESTED' ? 'Previous compiler output' : report?.status === 'FAILED' ? 'PDF build failed' : report?.items.length || report?.logTruncated ? 'PDF built with warnings' : 'PDF built successfully'}</h3>
    {report && <p>Build revision {report.revision ?? 'unknown'} · Current revision {revision}{dirty ? ' with unsaved edits' : ''}</p>}
    {stale && <p className="diagnostics-stale" role="status">{report?.revision === null ? 'The source revision for these diagnostics is unknown.' : 'These diagnostics are stale.'} Save your changes and build again before navigating.</p>}
    {error && <p role="alert">Could not load compiler diagnostics: {error} <button onClick={() => setRetry(value => value + 1)}>Retry diagnostics</button></p>}
    {!working && report && <>
      {(report.logTruncated || report.diagnosticsTruncated) && <p role="status">{report.logTruncated ? 'The compiler log was truncated after 64 KiB. ' : ''}{report.diagnosticsTruncated ? 'Only the first 200 diagnostics are shown. ' : ''}Full tool output is retained in project storage as preview.log.</p>}
      <ol>{report.items.map((item, index) => <li key={index}>
        <div className={`diagnostic-severity ${item.severity}`}>{item.severity === 'error' ? 'Error' : 'Warning'} · {item.title}</div>
        <p>{item.explanation}</p>
        {item.file && <p className="diagnostic-location">{item.file}{item.line !== null ? `:${item.line}` : ''}</p>}
        <details><summary>Compiler output</summary><pre>{item.raw}</pre></details>
        {(item.file === 'main.tex' || item.file === './main.tex') && Number.isSafeInteger(item.line) && item.line! > 0 && <button
          disabled={!canNavigate || stale || working || Boolean(error) || report.revision === null}
          onClick={() => { if (canNavigate && !stale && !error && report.revision !== null && item.line) onNavigate(item.line, report.revision) }}>Go to source line {item.line}</button>}
      </li>)}</ol>
    </>}
    <a href={`/api/jobs/${id}/preview/log`} target="_blank" rel="noreferrer">Open raw compiler log</a>
  </section>
}
