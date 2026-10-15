import { useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import type { ConversionReview, Job, SystemStatus } from './api'
import { api, downloadText, isWorking } from './api'
import { WorkspaceView } from './WorkspaceView'
import { useDiscardConfirmation } from './components/DiscardConfirmation'

const MAX_FILE = 20 * 1024 * 1024

export default function App() {
  const [system, setSystem] = useState<SystemStatus | null>(null)
  const [history, setHistory] = useState<Job[]>([])
  const [job, setJob] = useState<Job | null>(null)
  const [file, setFile] = useState<File | null>(null)
  const [template, setTemplate] = useState('article')
  const [source, setSource] = useState('')
  const [savedSource, setSavedSource] = useState('')
  const [sourceLoading, setSourceLoading] = useState(false)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [saving, setSaving] = useState(false)
  const [previewStarting, setPreviewStarting] = useState(false)
  const [dragging, setDragging] = useState(false)
  const [copied, setCopied] = useState(false)
  const [view, setView] = useState<'source' | 'pdf' | 'review'>('source')
  const [review, setReview] = useState<ConversionReview | null>(null)
  const currentId = useRef<string | null>(null)
  const copyTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const dirty = source !== savedSource
  const converting = busy || Boolean(job && isWorking(job.status))
  const compiling = previewStarting || Boolean(job && isWorking(job.previewStatus))
  const completed = job?.status === 'COMPLETED'
  const switchingDisabled = busy || saving || previewStarting
  const jobId = job?.id
  const jobStatus = job?.status
  const previewStatus = job?.previewStatus
  const { confirmDiscard, dialog } = useDiscardConfirmation(dirty)

  useEffect(() => {
    const controller = new AbortController()
    void Promise.all([api<SystemStatus>('/system', { signal: controller.signal }),
      api<Job[]>('/jobs', { signal: controller.signal })])
      .then(([status, jobs]) => { setSystem(status); setHistory(jobs) })
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
          setError(e instanceof Error ? e.message : 'Could not check progress.')
          timeout = setTimeout(poll, 4000)
        }
      }
    }
    timeout = setTimeout(poll, 500)
    return () => { controller.abort(); clearTimeout(timeout) }
  }, [jobId, jobStatus, previewStatus])

  useEffect(() => {
    if (!jobId || jobStatus !== 'COMPLETED') return
    const controller = new AbortController()
    void api<string>(`/jobs/${jobId}/source`, { signal: controller.signal }, true)
      .then(text => { setSource(text); setSavedSource(text) })
      .catch((e: Error) => { if (e.name !== 'AbortError') setError(e.message) })
      .finally(() => { if (!controller.signal.aborted) setSourceLoading(false) })
    return () => controller.abort()
  }, [jobId, jobStatus])

  useEffect(() => {
    if (!jobId || jobStatus !== 'COMPLETED') return
    const controller = new AbortController()
    void api<ConversionReview>(`/jobs/${jobId}/review`, { signal: controller.signal })
      .then(setReview).catch((error: Error) => { if (error.name !== 'AbortError') setError(error.message) })
    return () => controller.abort()
  }, [jobId, jobStatus, previewStatus])

  const discardChanges = confirmDiscard

  const selectFile = (next: File | undefined) => {
    if (!next) return
    if (!next.name.toLowerCase().endsWith('.docx')) { setError('Choose a Word .docx document. PDF and .doc files are not supported yet.'); return }
    if (next.size === 0 || next.size > MAX_FILE) { setError('Choose a non-empty DOCX file smaller than 20 MB.'); return }
    setError(''); setFile(next)
  }

  const startConversion = async (selected = file) => {
    if (!selected || !(await discardChanges())) return
    setBusy(true); setError(''); setView('source'); setSource(''); setSavedSource(''); setSourceLoading(true); setJob(null)
    currentId.current = null
    setReview(null)
    try {
      const body = new FormData()
      body.append('file', selected); body.append('template', template)
      const next = await api<Job>('/jobs', { method: 'POST', body })
      currentId.current = next.id
      setJob(next)
      setHistory(await api<Job[]>('/jobs'))
    } catch (e) { setError(e instanceof Error ? e.message : 'Upload failed. Try again.'); setSourceLoading(false) }
    finally { setBusy(false) }
  }

  const openJob = async (next: Job) => {
    if (switchingDisabled || next.id === job?.id || !(await discardChanges())) return
    currentId.current = next.id
    setReview(null)
    setJob(next); setFile(null); setTemplate(next.template); setSource(''); setSavedSource(''); setSourceLoading(true); setError(''); setView('source')
    void api<Job>(`/jobs/${next.id}`).then(latest => {
      if (currentId.current === latest.id) setJob(latest)
    }).catch((e: Error) => setError(e.message))
  }

  const reset = async () => {
    if (switchingDisabled || !(await discardChanges())) return
    currentId.current = null
    setReview(null)
    setJob(null); setFile(null); setSource(''); setSavedSource(''); setSourceLoading(false); setError(''); setView('source')
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
    if (!job) return null
    setSaving(true); setError('')
    try {
      const next = await api<Job>(`/jobs/${job.id}/source`, { method: 'PUT',
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ source, revision: job.revision }) })
      if (currentId.current !== next.id) return null
      setJob(next); setSavedSource(source)
      toast.success('Your source is saved')
      return next
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not save changes.'); return null }
    finally { setSaving(false) }
  }

  const buildPreview = async () => {
    if (!job || compiling) return
    setError(''); setPreviewStarting(true)
    try {
      const next = dirty ? await save() : job
      if (!next) return
      setJob(await api<Job>(`/jobs/${next.id}/preview`, { method: 'POST' }))
      setView('pdf')
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not start PDF compilation.') }
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
    if (!job) return
    const next = dirty ? await save() : job
    if (next) window.location.assign(`/api/jobs/${next.id}/download`)
  }

  const readyForActions = completed && !sourceLoading && Boolean(source)
  return <>
    <WorkspaceView model={{ system, history, job, file, template, source, sourceLoading, error, saving,
      dragging, copied, view, dirty, converting, compiling, switchingDisabled, completed, readyForActions, review,
      selectFile, setDragging, setTemplate, setView, setSource, setError,
      reset: () => void reset(), openJob: next => void openJob(next), trySample: () => void trySample(),
      startConversion: () => void startConversion(), save: () => void save(), copy: () => void copy(),
      download: () => void download(), downloadTex: () => downloadText(source, 'main.tex'),
      buildPreview: () => void buildPreview() }} />
    {dialog}
  </>
}
