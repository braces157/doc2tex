import { useEffect, useMemo, useRef, useState } from 'react'
import type { Job } from './api'
import { api, isMissingDocument } from './api'
import type { Draft } from './drafts'
import { findDraft, removeDraft, writeDraft } from './drafts'

interface SourceDocument { id: string; revision: number; source: string; savedSource: string }
interface LoadedSource {
  request: object
  document: SourceDocument | null
  loadError: string
  storageError: string
  recovery: Draft | null
  missing: boolean
}
const STORAGE_ERROR = 'Local draft recovery is unavailable. Save your changes or download the source before leaving.'

export function useSourceDocument(job: Job | null, onLoaded: (job: Job) => void) {
  const [loaded, setLoaded] = useState<LoadedSource | null>(null)
  const [attempt, setAttempt] = useState(0)
  const ownedDraft = useRef<Draft | null>(null)
  const jobId = job?.id
  const jobStatus = job?.status
  // A new identity immediately hides the previous document while a request runs.
  const request = useMemo(() => ({ jobId, jobStatus, attempt }), [jobId, jobStatus, attempt])
  const current = loaded?.request === request ? loaded : null
  const active = current?.document ?? null
  const recovery = current?.recovery ?? null

  useEffect(() => {
    ownedDraft.current = null
    if (!jobId || jobStatus !== 'COMPLETED') return
    const controller = new AbortController()
    const options = { signal: controller.signal }
    void (async () => {
      try {
        // Reject revision changes observed while loading the source.
        const before = await api<Job>(`/jobs/${jobId}`, options)
        const source = await api<string>(`/jobs/${jobId}/source`, options, true)
        const after = await api<Job>(`/jobs/${jobId}`, options)
        if (controller.signal.aborted) return
        if (before.revision !== after.revision) throw new Error('The source changed while opening it. Retry to load the latest saved version.')
        let recovery: Draft | null = null
        let storageError = ''
        try { recovery = findDraft(jobId, after.revision, source) }
        catch { storageError = STORAGE_ERROR }
        setLoaded({ request, document: { id: jobId, revision: after.revision, source, savedSource: source },
          recovery, storageError, loadError: '', missing: false })
        onLoaded(after)
      } catch (error) {
        if (!controller.signal.aborted) {
          const missing = isMissingDocument(error)
          let recovery: Draft | null = null
          let storageError = ''
          if (missing) {
            try { recovery = findDraft(jobId, -1, '') }
            catch { storageError = STORAGE_ERROR }
          }
          setLoaded({ request, document: null, recovery, storageError, missing,
            loadError: error instanceof Error ? error.message : 'Could not load the saved source.' })
        }
      }
    })()
    return () => controller.abort()
  }, [jobId, jobStatus, request, onLoaded])

  const changeSource = (source: string) => {
    if (!active || !current || recovery) return
    let storageError = ''
    try {
      if (source === active.savedSource) {
        if (ownedDraft.current) removeDraft(ownedDraft.current)
        ownedDraft.current = null
      } else ownedDraft.current = writeDraft(active.id, active.revision, source)
    } catch { storageError = STORAGE_ERROR }
    setLoaded({ ...current, document: { ...active, source }, storageError })
  }
  const discardDraft = () => {
    if (!recovery || !current) return
    try { removeDraft(recovery); setLoaded({ ...current, recovery: null }) }
    catch { setLoaded({ ...current, storageError: STORAGE_ERROR }) }
  }
  const restoreDraft = () => {
    if (!active || !current || !recovery || recovery.revision !== active.revision) return
    ownedDraft.current = recovery
    setLoaded({ ...current, document: { ...active, source: recovery.source }, recovery: null })
  }
  const markSaved = (saved: Job, source: string) => {
    if (!active || !current || active.id !== saved.id) return
    let storageError = current.storageError
    try {
      if (ownedDraft.current) removeDraft(ownedDraft.current)
      ownedDraft.current = null
    } catch { storageError = STORAGE_ERROR }
    setLoaded({ ...current, document: { ...active, revision: saved.revision, source, savedSource: source }, storageError })
  }
  // Explicit navigation discards only this window's changes after confirmation.
  const discardEdits = () => {
    if (!ownedDraft.current) return
    try { removeDraft(ownedDraft.current); ownedDraft.current = null }
    catch { if (current) setLoaded({ ...current, storageError: STORAGE_ERROR }) }
  }

  return { source: active?.source ?? '', revision: active?.revision, ready: Boolean(active),
    dirty: Boolean(active && active.source !== active.savedSource),
    loading: Boolean(jobId && jobStatus === 'COMPLETED' && !current),
    loadError: current?.loadError ?? '', storageError: current?.storageError ?? '', recovery,
    missing: current?.missing ?? false,
    changeSource, markSaved, discardEdits, restoreDraft, discardDraft,
    retry: () => setAttempt(value => value + 1) }
}
