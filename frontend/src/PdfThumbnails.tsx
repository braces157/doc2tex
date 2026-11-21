import { useEffect, useRef, useState } from 'react'
import { FilePdf } from '@phosphor-icons/react'
import type { RenderTask } from 'pdfjs-dist'
import { loadPdfProxy } from './pdfWorker'

export function PdfPageThumb({
  file,
  password,
  pageNumber,
  rotation,
  label,
}: {
  file: File
  password?: string
  pageNumber: number
  rotation: number
  label: string
}) {
  const stageRef = useRef<HTMLDivElement>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const [visible, setVisible] = useState(typeof IntersectionObserver === 'undefined')
  const [status, setStatus] = useState<'loading' | 'ready' | 'fallback'>('loading')

  useEffect(() => {
    if (visible || !stageRef.current || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver(
      entries => {
        if (entries.some(entry => entry.isIntersecting)) {
          setVisible(true)
          observer.disconnect()
        }
      },
      { rootMargin: '220px' },
    )
    observer.observe(stageRef.current)
    return () => observer.disconnect()
  }, [visible])

  useEffect(() => {
    if (!visible || !canvasRef.current) return
    const canvas = canvasRef.current
    let disposed = false
    let renderTask: RenderTask | undefined

    void loadPdfProxy(file, password)
      .then(async pdf => {
        if (disposed || pageNumber < 1 || pageNumber > pdf.numPages) return
        const page = await pdf.getPage(pageNumber)
        if (disposed) return
        const totalRotation = ((page.rotate || 0) + rotation) % 360
        const base = page.getViewport({ scale: 1, rotation: totalRotation })
        const scale = Math.min(168 / Math.max(1, base.width), 192 / Math.max(1, base.height))
        const viewport = page.getViewport({ scale, rotation: totalRotation })
        const ratio = Math.min(window.devicePixelRatio || 1, 2)
        const context = canvas.getContext('2d')
        if (!context) throw new Error('Canvas 2D unavailable')
        canvas.width = Math.max(1, Math.floor(viewport.width * ratio))
        canvas.height = Math.max(1, Math.floor(viewport.height * ratio))
        canvas.style.width = `${Math.max(1, Math.floor(viewport.width))}px`
        canvas.style.height = `${Math.max(1, Math.floor(viewport.height))}px`
        renderTask = page.render({
          canvas,
          canvasContext: context,
          viewport,
          transform: ratio === 1 ? undefined : [ratio, 0, 0, ratio, 0, 0],
        })
        await renderTask.promise
        if (!disposed) setStatus('ready')
      })
      .catch((failure: Error) => {
        if (!disposed && failure?.name !== 'RenderingCancelledException') {
          setStatus('fallback')
        }
      })

    return () => {
      disposed = true
      renderTask?.cancel()
    }
  }, [visible, file, password, pageNumber, rotation])

  return (
    <div className="pdf-thumb-stage" ref={stageRef}>
      {status === 'fallback' ? (
        <div className="pdf-thumb-fallback" aria-label={label}>
          <FilePdf size={24} />
          <span>Page {pageNumber}</span>
        </div>
      ) : (
        <>
          <canvas
            ref={canvasRef}
            className={status === 'ready' ? 'is-ready' : ''}
            role="img"
            aria-label={label}
          />
          {status === 'loading' && <span className="pdf-thumb-loading">Page {pageNumber}</span>}
        </>
      )}
    </div>
  )
}
