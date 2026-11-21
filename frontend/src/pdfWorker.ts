import { getDocument, GlobalWorkerOptions } from 'pdfjs-dist'
import type { PDFDocumentLoadingTask, PDFDocumentProxy } from 'pdfjs-dist'
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'

GlobalWorkerOptions.workerSrc = workerUrl

interface CachedPdfEntry {
  password?: string
  task: PDFDocumentLoadingTask | null
  cancelled: boolean
  promise: Promise<PDFDocumentProxy>
}

const docCache = new WeakMap<File, CachedPdfEntry>()

export function loadPdfProxy(file: File, password?: string): Promise<PDFDocumentProxy> {
  const existing = docCache.get(file)
  if (existing && existing.password === password && !existing.cancelled) return existing.promise
  if (existing) {
    existing.cancelled = true
    if (existing.task) void existing.task.destroy()
  }

  const entry: CachedPdfEntry = {
    password,
    task: null,
    cancelled: false,
    promise: Promise.resolve(null as unknown as PDFDocumentProxy),
  }
  entry.promise = file.arrayBuffer().then(data => {
    if (entry.cancelled) throw new Error('PDF loading cancelled')
    const task = getDocument({ data, password })
    entry.task = task
    return task.promise
  })
  docCache.set(file, entry)
  return entry.promise
}

export function releasePdfProxy(file: File) {
  const existing = docCache.get(file)
  if (!existing) return
  docCache.delete(file)
  existing.cancelled = true
  if (existing.task) void existing.task.destroy()
}
