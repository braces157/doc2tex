import { useEffect, useMemo, useState } from 'react'
import { api, downloadText, formatBytes } from './api'
import { downloadBibliography } from './bibliographyDownload'
import type { Job, SavedHistory } from './api'
import { Button } from './components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from './components/ui/dialog'
import { computeLineDiff } from './textDiff'

export function VersionHistory({ open, onOpenChange, job, disabled, onRestore }: {
  open: boolean; onOpenChange: (value: boolean) => void; job: Job | null; disabled: boolean
  onRestore: (revision: number) => Promise<boolean>
}) {
  const [attempt, setAttempt] = useState(0)
  const request = useMemo(() => ({ open, id: job?.id, revision: job?.revision, attempt }), [open, job?.id, job?.revision, attempt])
  const [loaded, setLoaded] = useState<{ request: object; history: SavedHistory | null; error: string } | null>(null)
  const current = loaded?.request === request ? loaded : null
  const history = current?.history ?? null
  const [selection, setSelection] = useState<{ id: string; revision: number } | null>(null)
  const selected = history?.items.find(item => selection?.id === job?.id && selection?.revision === item.revision) ?? history?.items[0]
  const sourceRequest = useMemo(() => ({ request, revision: selected?.revision }), [request, selected?.revision])
  const [preview, setPreview] = useState<{ request: object; source: string; error: string } | null>(null)
  const source = preview?.request === sourceRequest ? preview : null
  const [currentSnapshot, setCurrentSnapshot] = useState<{ request: object; source: string; error: string } | null>(null)
  const [previewMode, setPreviewMode] = useState<'source' | 'diff'>('source')
  const [restoring, setRestoring] = useState(false)
  const [restoreError, setRestoreError] = useState('')
  const [downloadingBibliography, setDownloadingBibliography] = useState(false)

  useEffect(() => {
    if (!request.open || !request.id) return
    const controller = new AbortController()
    void api<SavedHistory>(`/jobs/${request.id}/versions`, { signal: controller.signal })
      .then(history => { if (!controller.signal.aborted) setLoaded({ request, history, error: '' }) })
      .catch((e: Error) => { if (!controller.signal.aborted) setLoaded({ request, history: null, error: e.message }) })
    return () => controller.abort()
  }, [request])

  useEffect(() => {
    if (!request.open || !request.id || sourceRequest.revision === undefined) return
    const controller = new AbortController()
    void api<string>(`/jobs/${request.id}/versions/${sourceRequest.revision}/source`, { signal: controller.signal }, true)
      .then(source => { if (!controller.signal.aborted) setPreview({ request: sourceRequest, source, error: '' }) })
      .catch((e: Error) => { if (!controller.signal.aborted) setPreview({ request: sourceRequest, source: '', error: e.message }) })
    return () => controller.abort()
  }, [request, sourceRequest])

  useEffect(() => {
    if (!request.open || !request.id || previewMode !== 'diff' || !history) return
    if (selected?.revision === history.currentRevision) return
    if (currentSnapshot?.request === request) return
    const controller = new AbortController()
    void api<string>(`/jobs/${request.id}/versions/${history.currentRevision}/source`, { signal: controller.signal }, true)
      .then(text => { if (!controller.signal.aborted) setCurrentSnapshot({ request, source: text, error: '' }) })
      .catch((e: Error) => { if (!controller.signal.aborted) setCurrentSnapshot({ request, source: '', error: e.message }) })
    return () => controller.abort()
  }, [request, previewMode, history, selected?.revision, currentSnapshot?.request])

  const diffRows = useMemo(() => {
    if (previewMode !== 'diff' || !history || !source || source.error || !selected) return null
    if (selected.revision === history.currentRevision) return null
    if (currentSnapshot?.request !== request || currentSnapshot.error) return null
    return computeLineDiff(source.source, currentSnapshot.source)
  }, [previewMode, history, source, selected, currentSnapshot, request])

  const restore = async () => {
    if (!selected || restoring || disabled || !source || source.error) return
    setRestoring(true); setRestoreError('')
    try { if (await onRestore(selected.revision)) onOpenChange(false) }
    catch (error) { setRestoreError(error instanceof Error ? error.message : 'Could not restore this saved version.') }
    finally { setRestoring(false) }
  }

  const canDiff = Boolean(history && selected && selected.revision !== history.currentRevision)

  return <Dialog open={open} onOpenChange={value => { if (!restoring) { onOpenChange(value); setRestoreError(''); setPreviewMode('source') } }}>
    <DialogContent className="ui-versions-dialog"><DialogHeader><DialogTitle>Saved versions</DialogTitle><DialogDescription>Preview or download an earlier save. Restoring creates a new revision and requires a fresh PDF build.</DialogDescription></DialogHeader>
      {!current && <p role="status">Loading saved versions…</p>}
      {current?.error && <div role="alert"><p>{current.error}</p><Button variant="outline" onClick={() => setAttempt(value => value + 1)}>Retry versions</Button></div>}
      {history && <>
        {history.legacyHistoryUnavailable && <p className="ui-version-legacy">History begins at version {history.firstRevision} for this existing document. Earlier overwritten versions are unavailable.</p>}
        <p className="ui-version-storage">{history.items.length} / {history.maxVersions} versions · {formatBytes(history.bytesUsed)} / {formatBytes(history.maxBytes)}. History is preserved; saves stop when either limit is reached.</p>
        <div className="ui-version-layout"><nav aria-label="Saved source versions" className="ui-version-list">{history.items.map(version => <button key={version.revision} disabled={restoring} aria-current={selected?.revision === version.revision ? 'true' : undefined} onClick={() => { if (job) setSelection({ id: job.id, revision: version.revision }); setRestoreError('') }}>
          <strong>Version {version.revision}{version.revision === history.currentRevision ? ' · current' : ''}</strong>
          <span>{version.origin === 'converted' ? 'Original conversion' : version.origin === 'auto-fixed' ? 'Automatic fixes' : version.origin === 'legacy' ? 'Existing saved source' : version.origin === 'restored' ? `Restored from version ${version.restoredFrom}` : 'Saved edit'}</span>
          <time dateTime={version.createdAt}>{new Date(version.createdAt).toLocaleString()}</time>
        </button>)}</nav>
          <section className="ui-version-preview" aria-label="Version preview">
            {canDiff && <div className="ui-version-mode-bar" role="group" aria-label="Version comparison mode">
              <Button variant={previewMode === 'source' ? 'secondary' : 'ghost'} size="sm" onClick={() => setPreviewMode('source')}>Source</Button>
              <Button variant={previewMode === 'diff' ? 'secondary' : 'ghost'} size="sm" onClick={() => setPreviewMode('diff')}>Diff vs current</Button>
            </div>}
            {!source && <p role="status">Loading version source…</p>}
            {source?.error && <div role="alert"><p>{source.error}</p><Button variant="outline" onClick={() => setAttempt(value => value + 1)}>Retry version source</Button></div>}
            {source && !source.error && (previewMode === 'diff' && canDiff ? (
              currentSnapshot?.request !== request ? <p role="status">Loading current version for comparison…</p>
                : currentSnapshot.error ? <p role="alert">{currentSnapshot.error}</p>
                  : diffRows && <div className="eco-diff-box" aria-label={`Diff between version ${selected?.revision} and version ${history.currentRevision}`}>
                    {diffRows.truncated && <p className="eco-diff-note">Comparison truncated to first 600 lines.</p>}
                    {diffRows.rows.map((row, idx) => (
                      <div key={idx} className={`eco-diff-row is-${row.type}`}>
                        <span className="eco-diff-sign">{row.type === 'add' ? '+' : row.type === 'del' ? '-' : ' '}</span>
                        <code>{row.text || ' '}</code>
                      </div>
                    ))}
                  </div>
            ) : (
              <label>Version {selected?.revision} · read only<textarea readOnly aria-label="Saved version source" spellCheck={false} value={source.source} /></label>
            ))}
            {selected?.bibliographyFilename && <p className="ui-version-asset-note">This version includes {selected.bibliographyFilename} ({formatBytes(selected.assetBytes ?? 0)} of bibliography/font assets). Restoring also restores those matching assets. Export the restored project to include its fonts.</p>}
          </section>
        </div>
        {restoreError && <p role="alert">{restoreError}</p>}
        <div className="ui-version-actions"><Button variant="outline" disabled={!source || Boolean(source.error)} onClick={() => { if (source && selected) downloadText(source.source, `main-version-${selected.revision}.tex`) }}>Download version</Button>
          {selected?.bibliographyFilename && <Button variant="outline" disabled={restoring || downloadingBibliography} onClick={() => { if (!job) return; setRestoreError(''); setDownloadingBibliography(true); void downloadBibliography(`/jobs/${job.id}/versions/${selected.revision}/bibliography`, `references-v${selected.revision}.bib`).catch(error => setRestoreError(error instanceof Error ? error.message : 'Could not download version bibliography.')).finally(() => setDownloadingBibliography(false)) }}>Download version bibliography</Button>}
          <Button disabled={disabled || restoring || !source || Boolean(source.error)} onClick={() => void restore()}>{restoring ? 'Restoring…' : `Restore version ${selected?.revision ?? ''}`}</Button></div>
      </>}
    </DialogContent>
  </Dialog>
}
