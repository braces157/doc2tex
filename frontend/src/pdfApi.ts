export interface PdfMetadata {
  title: string
  author: string
  subject: string
  keywords: string
  creator: string
  producer: string
  creationDate: string
  modificationDate: string
}

export interface PdfPageInfo {
  number: number
  widthMm: number
  heightMm: number
  rotation: number
  charCount: number
  imageCount: number
  annotationCount: number
}

export interface PdfOutlineItem {
  title: string
  level: number
  pageNumber: number | null
}

export interface PdfInspection {
  filename: string
  bytes: number
  pdfVersion: string
  pageCount: number
  encrypted: boolean
  hasMetadata: boolean
  hasXmpMetadata: boolean
  hasOutline: boolean
  embeddedImageCount: number
  annotationCount: number
  fontCount: number
  metadata: PdfMetadata
  pages: PdfPageInfo[]
  outline: PdfOutlineItem[]
  warnings: string[]
}

export interface PdfAssemblePageSpec {
  fileIndex: number
  pageNumber: number
  rotation: number
}

export interface PdfAssemblePlan {
  pages: PdfAssemblePageSpec[]
  scrubMetadata: boolean
  stripAnnotations: boolean
  watermarkText: string
  watermarkOpacity: number
  pageNumbers: boolean
  outputName: string
  passwords?: string[]
}

export interface PdfPageText {
  pageNumber: number
  text: string
}

export interface PdfTextExtract {
  filename: string
  pageCount: number
  totalChars: number
  truncated: boolean
  pages: PdfPageText[]
}

export interface PdfMarkdownExtract {
  filename: string
  pageCount: number
  totalChars: number
  truncated: boolean
  markdown: string
}

export interface BinaryDownload {
  blob: Blob
  filename: string
}

export interface LoadedPdf {
  uid: string
  file: File
  password?: string
  inspection: PdfInspection | null
  loading: boolean
  needsPassword: boolean
  error: string
}

export interface OrganizerPage {
  key: string
  docUid: string
  docName: string
  pageNumber: number
  rotation: number
  widthMm: number
  heightMm: number
}

export interface ExtractOutput {
  docUid: string
  kind: 'text' | 'markdown'
  content: string
  totalChars: number
  pageCount: number
  truncated: boolean
}

async function readErrorMessage(response: Response): Promise<string> {
  const payload = await response.json().catch(() => ({})) as { message?: string }
  return payload.message || `The PDF request failed (${response.status}). Try again.`
}

function parseDownloadFilename(header: string | null, fallback: string): string {
  if (!header) return fallback
  const utf8 = header.match(/filename\*=UTF-8''([^;]+)/i)
  if (utf8?.[1]) {
    try { return decodeURIComponent(utf8[1].replace(/^"|"$/g, '')) } catch { /* ignore malformed encoding */ }
  }
  const ascii = header.match(/filename="?([^";]+)"?/i)
  return ascii?.[1]?.trim() || fallback
}

async function postJson<T>(path: string, file: File, password?: string): Promise<T> {
  const body = new FormData()
  body.append('file', file)
  if (password) body.append('password', password)
  const response = await fetch(`/api/pdf${path}`, { method: 'POST', body })
  if (!response.ok) throw new Error(await readErrorMessage(response))
  return response.json() as Promise<T>
}

export const inspectPdf = (file: File, password?: string) => postJson<PdfInspection>('/inspect', file, password)

export const extractPdfText = (file: File, password?: string) => postJson<PdfTextExtract>('/extract/text', file, password)

export const extractPdfMarkdown = (file: File, password?: string) =>
  postJson<PdfMarkdownExtract>('/extract/markdown', file, password)

export async function extractPdfImages(file: File, password?: string): Promise<BinaryDownload> {
  const body = new FormData()
  body.append('file', file)
  if (password) body.append('password', password)
  const response = await fetch('/api/pdf/extract/images', { method: 'POST', body })
  if (!response.ok) throw new Error(await readErrorMessage(response))
  const fallback = `${file.name.replace(/\.pdf$/i, '') || 'document'}-images.zip`
  return {
    blob: await response.blob(),
    filename: parseDownloadFilename(response.headers.get('content-disposition'), fallback),
  }
}

export async function assemblePdf(files: File[], plan: PdfAssemblePlan): Promise<BinaryDownload> {
  const body = new FormData()
  for (const file of files) body.append('files', file)
  body.append('plan', JSON.stringify(plan))
  const response = await fetch('/api/pdf/assemble', { method: 'POST', body })
  if (!response.ok) throw new Error(await readErrorMessage(response))
  return {
    blob: await response.blob(),
    filename: parseDownloadFilename(response.headers.get('content-disposition'), plan.outputName || 'assembled.pdf'),
  }
}

export function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

/** Parse expressions like "1-3, 5, 8-10" into 1-based page numbers, preserving order and rejecting invalid ranges. */
export function parsePageRange(input: string, maxPage: number): number[] {
  const trimmed = input.trim()
  if (!trimmed || maxPage < 1) throw new Error('Enter page numbers or ranges such as 1-3, 5.')
  const result: number[] = []
  for (const rawPart of trimmed.split(',')) {
    const part = rawPart.trim()
    if (!part) throw new Error('Remove empty commas in the page range.')
    const single = part.match(/^(\d+)$/)
    if (single) {
      const page = Number(single[1])
      if (page < 1 || page > maxPage) throw new Error(`Page ${page} is out of range (1–${maxPage}).`)
      result.push(page)
      continue
    }
    const range = part.match(/^(\d+)\s*-\s*(\d+)$/)
    if (!range) throw new Error(`Invalid range segment "${part}". Use numbers or ranges like 1-3, 5.`)
    const start = Number(range[1])
    const end = Number(range[2])
    if (start < 1 || end < 1 || start > maxPage || end > maxPage || start > end) {
      throw new Error(`Invalid page range "${part}" for ${maxPage} total pages.`)
    }
    for (let page = start; page <= end; page++) result.push(page)
  }
  return result
}
