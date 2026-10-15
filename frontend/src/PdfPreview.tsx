import { useEffect, useRef, useState } from 'react'
import { ArrowDown, CaretLeft, CaretRight, WarningCircle } from '@phosphor-icons/react'
import { getDocument, GlobalWorkerOptions } from 'pdfjs-dist'
import type { PDFDocumentProxy, RenderTask } from 'pdfjs-dist'
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'

GlobalWorkerOptions.workerSrc = workerUrl

export default function PdfPreview({ id, revision }: { id: string; revision: number }) {
  const [pdf, setPdf] = useState<PDFDocumentProxy | null>(null)
  const [pageNumber, setPageNumber] = useState(1)
  const [renderedPage, setRenderedPage] = useState(0)
  const [width, setWidth] = useState(0)
  const [error, setError] = useState('')
  const container = useRef<HTMLDivElement>(null)
  const canvas = useRef<HTMLCanvasElement>(null)
  const url = `/api/jobs/${id}/preview?r=${revision}`

  useEffect(() => {
    const task = getDocument({ url })
    let disposed = false
    void task.promise.then(document => { if (!disposed) setPdf(document) })
      .catch(() => { if (!disposed) setError('The PDF could not be displayed. Download it to open in your PDF reader.') })
    return () => { disposed = true; void task.destroy() }
  }, [url])

  useEffect(() => {
    if (!container.current) return
    const observer = new ResizeObserver(entries => setWidth(Math.floor(entries[0].contentRect.width)))
    observer.observe(container.current)
    return () => observer.disconnect()
  }, [])

  useEffect(() => {
    if (!pdf || !canvas.current || !width) return
    const element = canvas.current
    let disposed = false
    let rendering: RenderTask | undefined
    void pdf.getPage(pageNumber).then(async page => {
      if (disposed) return
      const viewport = page.getViewport({ scale: Math.max(100, width - 36) / page.getViewport({ scale: 1 }).width })
      const ratio = Math.min(window.devicePixelRatio || 1, 2)
      const context = element.getContext('2d')
      if (!context) throw new Error('Canvas is unavailable')
      element.width = Math.floor(viewport.width * ratio)
      element.height = Math.floor(viewport.height * ratio)
      element.style.width = `${Math.floor(viewport.width)}px`
      element.style.height = `${Math.floor(viewport.height)}px`
      rendering = page.render({ canvas: element, canvasContext: context, viewport,
        transform: ratio === 1 ? undefined : [ratio, 0, 0, ratio, 0, 0] })
      await rendering.promise
      if (!disposed) setRenderedPage(pageNumber)
    }).catch((failure: Error) => {
      if (!disposed && failure.name !== 'RenderingCancelledException') setError('This PDF page could not be rendered. Download the PDF to view it.')
    })
    return () => { disposed = true; rendering?.cancel() }
  }, [pdf, pageNumber, width])

  return <div className="pdf-viewer">
    <div className="pdf-navigation"><div className="flex items-center gap-2"><button className="icon-button" aria-label="Previous PDF page" disabled={pageNumber <= 1 || renderedPage !== pageNumber} onClick={() => setPageNumber(page => page - 1)}><CaretLeft size={14} /></button><span>PAGE {pageNumber} / {pdf?.numPages ?? '—'}</span><button className="icon-button" aria-label="Next PDF page" disabled={!pdf || pageNumber >= pdf.numPages || renderedPage !== pageNumber} onClick={() => setPageNumber(page => page + 1)}><CaretRight size={14} /></button></div><a href={url} download="preview.pdf" className="pdf-download"><ArrowDown size={13} /> PDF</a></div>
    <div className="pdf-pages" ref={container} aria-busy={!error && renderedPage !== pageNumber}>
      {error ? <div className="pdf-error" role="alert"><WarningCircle size={25} /><p>{error}</p></div> : <><canvas ref={canvas} role="img" aria-label={`PDF preview, page ${pageNumber} of ${pdf?.numPages ?? 'document'}`} />{renderedPage !== pageNumber && <span className="pdf-loading">Rendering page…</span>}</>}
    </div>
  </div>
}
