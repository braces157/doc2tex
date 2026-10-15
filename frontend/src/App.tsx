import { useCallback, useEffect, useEffectEvent, useRef, useState } from 'react'
import { toast } from 'sonner'
import type { ConversionReview, Job, SystemStatus } from './api'
import { api, downloadText, isWorking, isMissingDocument } from './api'
import { WorkspaceView } from './WorkspaceView'
import { useDiscardConfirmation } from './components/DiscardConfirmation'
import { useSourceDocument } from './useSourceDocument'
import { clearJobDrafts, DELETED_JOB } from './drafts'
import type { LatexEditorHandle } from './LatexEditor'

const MAX_FILE = 20 * 1024 * 1024
const ACTIVE_JOB = 'doc2tex:active-job'

export default function App() {
  const [system, setSystem] = useState<SystemStatus | null>(null)
  const [history, setHistory] = useState<Job[]>([])
  const [job, setJob] = useState<Job | null>(null)
  const [file, setFile] = useState<File | null>(null)
  const [template, setTemplate] = useState('article')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [saving, setSaving] = useState(false)
  const [previewStarting, setPreviewStarting] = useState(false)
  const [dragging, setDragging] = useState(false)
  const [copied, setCopied] = useState(false)
  const [view, setView] = useState<'source' | 'pdf' | 'review'>('source')
  const [review, setReview] = useState<ConversionReview | null>(null)
  const [deletedId, setDeletedId] = useState<string | null>(null)
  const currentId = useRef<string | null>(null)
  const navigationVersion = useRef(0)
  const editorRef = useRef<LatexEditorHandle>(null)
  const composition = useRef({ active: false, endedAt: 0 })
  const commandKey = /Mac|iPhone|iPad/.test(navigator.platform) ? '⌘' : 'Ctrl'
  const copyTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const sourceDocument = useSourceDocument(job, setJob)
  const { source, dirty, loading: sourceLoading } = sourceDocument
  const missingDocument = Boolean(job && (deletedId === job.id || sourceDocument.missing))
  const converting = busy || Boolean(job && isWorking(job.status))
  const compiling = previewStarting || Boolean(job && isWorking(job.previewStatus))
  const completed = job?.status === 'COMPLETED'
  const switchingDisabled = busy || saving || previewStarting
  const jobId = job?.id
  const jobStatus = job?.status
  const previewStatus = job?.previewStatus
  const { confirmDiscard, dialog } = useDiscardConfirmation(dirty)
  const noteMissing = useCallback((id: string, error: unknown) => {
    if (!isMissingDocument(error)) return
    setDeletedId(id)
    if (error.status === 404) {
      try { if (sessionStorage.getItem(ACTIVE_JOB) === id) sessionStorage.removeItem(ACTIVE_JOB) }
      catch { /* In-memory text stays downloadable when browser storage is unavailable. */ }
    }
  }, [])
  const rememberJob = (id: string | null) => {
    try {
      if (id) sessionStorage.setItem(ACTIVE_JOB, id)
      else sessionStorage.removeItem(ACTIVE_JOB)
    } catch { /* Opening documents still works when browser storage is disabled. */ }
  }

  useEffect(() => {
    const controller = new AbortController()
    const initialNavigation = navigationVersion.current
    void Promise.all([api<SystemStatus>('/system', { signal: controller.signal }),
      api<Job[]>('/jobs', { signal: controller.signal })])
      .then(async ([status, jobs]) => {
        if (controller.signal.aborted) return
        setSystem(status); setHistory(jobs)
        let id: string | null
        try { id = sessionStorage.getItem(ACTIVE_JOB) }
        catch { return /* History remains available when session storage is disabled. */ }
        if (!id) return
        try {
          const previous = await api<Job>(`/jobs/${encodeURIComponent(id)}`, { signal: controller.signal })
          if (!controller.signal.aborted && navigationVersion.current === initialNavigation) {
            currentId.current = previous.id; setJob(previous); setTemplate(previous.template)
          }
        } catch (e) {
          if (!controller.signal.aborted && navigationVersion.current === initialNavigation)
            setError(e instanceof Error ? `Could not reopen your active document: ${e.message}` : 'Could not reopen your active document. Find it in All documents.')
        }
      })
      .catch((e: Error) => { if (e.name !== 'AbortError') setError('Could not connect to the conversion service. Check that Spring Boot is running.') })
    return () => { controller.abort(); if (copyTimer.current) clearTimeout(copyTimer.current) }
  }, [])

  useEffect(() => {
    if (!dirty) return
    const onLeave = (event: BeforeUnloadEvent) => { event.preventDefault() }
    window.addEventListener('beforeunload', onLeave)
    return () => window.removeEventListener('beforeunload', onLeave)
  }, [dirty])

  useEffect(() => {
    if (!jobId) return
    const controller = new AbortController()
    const check = () => {
      void api<Job>(`/jobs/${jobId}`, { signal: controller.signal }).catch(error => {
        if (!controller.signal.aborted && currentId.current === jobId) noteMissing(jobId, error)
      })
    }
    const onStorage = (event: StorageEvent) => {
      if (event.key !== DELETED_JOB || !event.newValue) return
      try {
        if ((JSON.parse(event.newValue) as { id?: string }).id === jobId) check()
      } catch { /* Ignore malformed browser notices. Confirm deletion with the server. */ }
    }
    window.addEventListener('focus', check)
    window.addEventListener('storage', onStorage)
    return () => { controller.abort(); window.removeEventListener('focus', check); window.removeEventListener('storage', onStorage) }
  }, [jobId, noteMissing])

  useEffect(() => {
    if (!jobId || (!isWorking(jobStatus ?? '') && !isWorking(previewStatus ?? ''))) return
    const controller = new AbortController()
    let timeout: ReturnType<typeof setTimeout>
    const poll = async () => {
      try {
        const next = await api<Job>(`/jobs/${jobId}`, { signal: controller.signal })
        if (controller.signal.aborted || currentId.current !== next.id) return
        setJob(next)
        setHistory(previous => previous.map(item => item.id === next.id ? next : item))
        if (isWorking(next.status) || isWorking(next.previewStatus)) timeout = setTimeout(poll, 1200)
      } catch (e) {
        if (!controller.signal.aborted) {
          if (isMissingDocument(e)) { noteMissing(jobId, e); return }
          setError(e instanceof Error ? e.message : 'Could not check progress.')
          timeout = setTimeout(poll, 4000)
        }
      }
    }
    timeout = setTimeout(poll, 500)
    return () => { controller.abort(); clearTimeout(timeout) }
  }, [jobId, jobStatus, previewStatus, noteMissing])

  useEffect(() => {
    if (!jobId || jobStatus !== 'COMPLETED') return
    const controller = new AbortController()
    void api<ConversionReview>(`/jobs/${jobId}/review`, { signal: controller.signal })
      .then(setReview).catch((error: Error) => { if (error.name !== 'AbortError') setError(error.message) })
    return () => controller.abort()
  }, [jobId, jobStatus, previewStatus])

  const discardChanges = async () => {
    if (!(await confirmDiscard())) return false
    sourceDocument.discardEdits()
    return true
  }

  const selectFile = (next: File | undefined) => {
    if (!next) return
    if (!next.name.toLowerCase().endsWith('.docx')) { setError('Choose a Word .docx document. PDF and .doc files are not supported yet.'); return }
    if (next.size === 0 || next.size > MAX_FILE) { setError('Choose a non-empty DOCX file smaller than 20 MB.'); return }
    setError(''); setFile(next)
  }

  const startConversion = async (selected = file) => {
    if (!selected || switchingDisabled || !(await confirmDiscard())) return
    setBusy(true); setError('')
    try {
      const body = new FormData()
      body.append('file', selected); body.append('template', template)
      const next = await api<Job>('/jobs', { method: 'POST', body })
      sourceDocument.discardEdits()
      navigationVersion.current++
      currentId.current = next.id
      rememberJob(next.id)
      setJob(next); setReview(null); setView('source')
      setHistory(previous => [next, ...previous.filter(item => item.id !== next.id)])
    } catch (e) { setError(e instanceof Error ? e.message : 'Upload failed. Try again.') }
    finally { setBusy(false) }
  }

  const openJob = async (next: Job) => {
    if (switchingDisabled || next.id === job?.id || !(await discardChanges())) return
    navigationVersion.current++
    currentId.current = next.id
    rememberJob(next.id)
    setReview(null)
    setJob(next); setFile(null); setTemplate(next.template); setError(''); setView('source')
  }

  const reset = async () => {
    if (switchingDisabled || !(await discardChanges())) return
    navigationVersion.current++
    currentId.current = null
    rememberJob(null)
    setReview(null)
    setJob(null); setFile(null); setError(''); setView('source')
  }

  const trySample = async () => {
    setError('')
    try {
      const response = await fetch('/api/sample.docx')
      if (!response.ok) throw new Error('Could not load the sample document.')
      const sample = new File([await response.blob()], 'field-notes.docx', { type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' })
      setFile(sample)
      await startConversion(sample)
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not load the sample.') }
  }

  const save = async () => {
    if (!job || missingDocument || !source.trim() || !sourceDocument.ready || sourceDocument.recovery || saving || compiling || busy) return null
    setSaving(true); setError('')
    try {
      const next = await api<Job>(`/jobs/${job.id}/source`, { method: 'PUT',
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ source, revision: sourceDocument.revision }) })
      if (currentId.current !== next.id) return null
      setJob(next); sourceDocument.markSaved(next, source)
      setHistory(previous => previous.map(item => item.id === next.id ? next : item))
      toast.success('Your source is saved')
      return next
    } catch (e) { noteMissing(job.id, e); setError(e instanceof Error ? e.message : 'Could not save changes.'); return null }
    finally { setSaving(false) }
  }

  const buildPreview = async () => {
    if (!job || missingDocument || !source.trim() || compiling || saving || !system?.tectonic || !sourceDocument.ready || sourceDocument.recovery || busy) return
    if (!dirty && job.previewStatus === 'READY') { setView('pdf'); return }
    setError(''); setPreviewStarting(true)
    try {
      const next = dirty ? await save() : job
      if (!next) return
      setJob(await api<Job>(`/jobs/${next.id}/preview`, { method: 'POST' }))
      setView('pdf')
    } catch (e) { noteMissing(job.id, e); setError(e instanceof Error ? e.message : 'Could not start PDF compilation.') }
    finally { setPreviewStarting(false) }
  }

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(source)
      setCopied(true)
      toast.success('LaTeX source copied')
      if (copyTimer.current) clearTimeout(copyTimer.current)
      copyTimer.current = setTimeout(() => setCopied(false), 2000)
    } catch { setError('Clipboard access is unavailable. Select the source and copy it manually.') }
  }

  const download = async () => {
    if (!job || missingDocument || !source.trim() || !sourceDocument.ready || sourceDocument.recovery || busy) return
    const next = dirty ? await save() : job
    if (next) window.location.assign(`/api/jobs/${next.id}/download`)
  }

  const renamed = (next: Job) => {
    if (currentId.current === next.id) setJob(next)
    setHistory(previous => previous.map(item => item.id === next.id ? next : item))
  }

  const restoreVersion = async (version: number) => {
    if (!job || missingDocument || !sourceDocument.ready || sourceDocument.recovery || saving || compiling || busy || !(await confirmDiscard())) return false
    setSaving(true); setError('')
    try {
      const result = await api<{ job: Job; source: string }>(`/jobs/${job.id}/versions/${version}/restore`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ revision: sourceDocument.revision }),
      })
      if (currentId.current !== result.job.id) return false
      setJob(result.job); sourceDocument.markSaved(result.job, result.source)
      setHistory(previous => previous.map(item => item.id === result.job.id ? result.job : item))
      setView('source'); toast.success(`Version ${version} restored as version ${result.job.revision}`)
      return true
    } catch (error) { noteMissing(job.id, error); throw error }
    finally { setSaving(false) }
  }

  const deleted = (removed: Job) => {
    // This callback runs only after the server confirms all job-owned files are gone.
    try { clearJobDrafts(removed.id) }
    catch { toast.warning('Document removed. Browser draft cleanup is unavailable; clear local storage manually if needed.') }
    navigationVersion.current++
    try { if (sessionStorage.getItem(ACTIVE_JOB) === removed.id) sessionStorage.removeItem(ACTIVE_JOB) }
    catch { /* Server deletion has succeeded; storage cleanup cannot undo it. */ }
    setHistory(previous => previous.filter(item => item.id !== removed.id))
    if (currentId.current === removed.id) {
      currentId.current = null; rememberJob(null)
      setJob(null); setFile(null); setReview(null); setView('source'); setError('')
    }
  }

  const readyForActions = completed && !missingDocument && sourceDocument.ready && !sourceDocument.recovery && !busy && Boolean(source.trim())

  useEffect(() => {
    const start = () => { composition.current.active = true }
    const end = () => { composition.current.active = false; composition.current.endedAt = Date.now() }
    window.addEventListener('compositionstart', start, true)
    window.addEventListener('compositionend', end, true)
    return () => { window.removeEventListener('compositionstart', start, true); window.removeEventListener('compositionend', end, true) }
  }, [])

  const shortcut = useEffectEvent((event: KeyboardEvent) => {
      const modifier = commandKey === '⌘' ? event.metaKey : event.ctrlKey
      if (!modifier || event.altKey || event.shiftKey || event.repeat || event.defaultPrevented
        || event.isComposing || event.keyCode === 229 || composition.current.active
        || Date.now() - composition.current.endedAt < 50 || editorRef.current?.isComposing()
        || document.querySelector('[role="dialog"][data-state="open"], [role="alertdialog"][data-state="open"]')) return
      const key = event.key.toLowerCase()
      if (key !== 's' && key !== 'enter') return
      event.preventDefault(); event.stopPropagation()
      if (!readyForActions || saving || compiling) return
      if (key === 's' && dirty) void save()
      if (key === 'enter') void buildPreview()
    })
  useEffect(() => {
    window.addEventListener('keydown', shortcut, true)
    return () => window.removeEventListener('keydown', shortcut, true)
  }, [])

  return <>
    <WorkspaceView model={{ system, history, job, file, template, source, sourceLoading, error, saving,
      dragging, copied, view, dirty, converting, compiling, switchingDisabled, completed, readyForActions, review,
      sourceError: sourceDocument.loadError, storageError: sourceDocument.storageError,
      recovery: sourceDocument.recovery, sourceRevision: sourceDocument.revision,
      missingDocument, renamed, deleted,
      versionsAvailable: completed && sourceDocument.ready && !sourceDocument.recovery && !missingDocument && !busy,
      restoreVersion,
      editorRef, commandKey,
      retrySource: sourceDocument.retry, restoreDraft: sourceDocument.restoreDraft, discardDraft: sourceDocument.discardDraft,
      selectFile, setDragging, setTemplate, setView, setSource: sourceDocument.changeSource, setError,
      reset: () => void reset(), openJob: next => void openJob(next), trySample: () => void trySample(),
      startConversion: () => void startConversion(), save: () => void save(), copy: () => void copy(),
      download: () => void download(), downloadTex: () => downloadText(source, 'main.tex'),
      buildPreview: () => void buildPreview() }} />
    {dialog}
  </>
}
