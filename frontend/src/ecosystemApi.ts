import type { BinaryDownload } from './pdfApi'

export interface FormatOption {
  id: string
  label: string
  extension: string
}

export interface FormatCatalog {
  pandocReady: boolean
  legacyDocReady: boolean
  inputs: FormatOption[]
  outputs: FormatOption[]
}

export interface PdfToSourceResult {
  filename: string
  format: 'markdown' | 'latex'
  pageCount: number
  headingsFound: number
  imagesExtracted: number
  twoColumnDetected: boolean
  aiLayoutUsed: boolean
  warnings: string[]
  content: string
}

export interface DiffLine {
  type: 'same' | 'add' | 'del'
  text: string
}

export interface PdfPageDiff {
  page: number
  identical: boolean
  added: number
  removed: number
  lines: DiffLine[]
}

export interface PdfDiffReport {
  leftName: string
  rightName: string
  leftPages: number
  rightPages: number
  comparedPages: number
  matchingPages: number
  changedPages: number
  addedLines: number
  removedLines: number
  truncated: boolean
  pages: PdfPageDiff[]
}

export interface VisualPageDiff {
  page: number
  pixelDiffRatio: number
  width: number
  height: number
  leftPngBase64: string
  rightPngBase64: string
  diffPngBase64: string
}

export interface DocxInspection {
  filename: string
  totalBytes: number
  mediaBytes: number
  mediaCount: number
  commentCount: number
  trackedChangeCount: number
  externalLinkCount: number
  metadata: Record<string, string>
  largestMedia: { name: string; bytes: number }[]
  recommendations: string[]
}

export interface DocxCleanResult extends BinaryDownload {
  originalBytes: number
  cleanedBytes: number
  imagesOptimized: number
}

export interface TemplateDescriptor {
  id: string
  name: string
  category: string
  description: string
  defaultPaper: 'a4' | 'letter'
  defaultFontSize: number
  twoColumn: boolean
}

export interface TemplateRequest {
  templateId: string
  title: string
  author: string
  affiliation: string
  abstractText: string
  date: string
  paper: string
  fontSize: number
}

export interface GeneratedTemplate {
  templateId: string
  filename: string
  source: string
}

export interface ExtractedRef {
  key: string
  type: string
  title: string
  author: string
  year: string
  doi: string
  arxiv: string
  raw: string
}

export interface ExtractionReport {
  sourceName: string
  totalFound: number
  entries: ExtractedRef[]
  bibtex: string
  warnings: string[]
}

export interface BatchDownload extends BinaryDownload {
  total: number
  succeeded: number
  failed: number
}

async function readError(response: Response): Promise<string> {
  const payload = await response.json().catch(() => ({})) as { message?: string }
  return payload.message || `Request failed (${response.status}). Try again.`
}

function filenameFromDisposition(header: string | null, fallback: string): string {
  if (!header) return fallback
  const utf8 = header.match(/filename\*=UTF-8''([^;]+)/i)
  if (utf8?.[1]) {
    try { return decodeURIComponent(utf8[1].replace(/^"|"$/g, '')) } catch { /* fallback below */ }
  }
  const ascii = header.match(/filename="?([^";]+)"?/i)
  return ascii?.[1]?.trim() || fallback
}

export async function fetchFormatCatalog(): Promise<FormatCatalog> {
  const res = await fetch('/api/convert/formats')
  if (!res.ok) throw new Error(await readError(res))
  return res.json() as Promise<FormatCatalog>
}

export async function convertFile(
  file: File,
  targetFormat: string,
  toc: boolean,
): Promise<BinaryDownload & { warnings: string }> {
  const body = new FormData()
  body.append('file', file)
  body.append('targetFormat', targetFormat)
  body.append('toc', String(toc))
  const res = await fetch('/api/convert', { method: 'POST', body })
  if (!res.ok) throw new Error(await readError(res))
  return {
    blob: await res.blob(),
    filename: filenameFromDisposition(res.headers.get('content-disposition'), `converted.${targetFormat}`),
    warnings: res.headers.get('x-doc2tex-warnings') || '',
  }
}

export const jobExportUrl = (jobId: string, format: string) =>
  `/api/jobs/${encodeURIComponent(jobId)}/export/${encodeURIComponent(format)}`

export async function exportJobFile(
  jobId: string,
  format: string,
  fallbackName: string,
): Promise<BinaryDownload & { warnings: string }> {
  const res = await fetch(jobExportUrl(jobId, format))
  if (!res.ok) throw new Error(await readError(res))
  return {
    blob: await res.blob(),
    filename: filenameFromDisposition(res.headers.get('content-disposition'), fallbackName),
    warnings: res.headers.get('x-doc2tex-warnings') || '',
  }
}

export async function convertPdfToSource(
  file: File,
  targetFormat: 'markdown' | 'latex',
  aiLayout: boolean,
  password?: string,
): Promise<PdfToSourceResult> {
  const body = new FormData()
  body.append('file', file)
  body.append('targetFormat', targetFormat)
  body.append('aiLayout', String(aiLayout))
  if (password) body.append('password', password)
  const res = await fetch('/api/pdf/to-source', { method: 'POST', body })
  if (!res.ok) throw new Error(await readError(res))
  return res.json() as Promise<PdfToSourceResult>
}

export async function downloadPdfToSource(
  file: File,
  targetFormat: 'markdown' | 'latex-zip' | 'docx',
  aiLayout: boolean,
  password?: string,
): Promise<BinaryDownload> {
  const body = new FormData()
  body.append('file', file)
  body.append('targetFormat', targetFormat)
  body.append('aiLayout', String(aiLayout))
  if (password) body.append('password', password)
  const res = await fetch('/api/pdf/to-source/download', { method: 'POST', body })
  if (!res.ok) throw new Error(await readError(res))
  return {
    blob: await res.blob(),
    filename: filenameFromDisposition(res.headers.get('content-disposition'), 'converted'),
  }
}

export async function diffPdfs(left: File, right: File): Promise<PdfDiffReport> {
  const body = new FormData()
  body.append('left', left)
  body.append('right', right)
  const res = await fetch('/api/diff/pdf', { method: 'POST', body })
  if (!res.ok) throw new Error(await readError(res))
  return res.json() as Promise<PdfDiffReport>
}

export async function renderPdfDiffPage(left: File, right: File, page: number): Promise<VisualPageDiff> {
  const body = new FormData()
  body.append('left', left)
  body.append('right', right)
  body.append('page', String(page))
  const res = await fetch('/api/diff/pdf/page', { method: 'POST', body })
  if (!res.ok) throw new Error(await readError(res))
  return res.json() as Promise<VisualPageDiff>
}

export async function inspectDocx(file: File): Promise<DocxInspection> {
  const body = new FormData()
  body.append('file', file)
  const res = await fetch('/api/docx/inspect', { method: 'POST', body })
  if (!res.ok) throw new Error(await readError(res))
  return res.json() as Promise<DocxInspection>
}

export async function cleanDocx(
  file: File,
  options: { stripMetadata: boolean; stripComments: boolean; compressMedia: boolean; maxImagePx: number },
): Promise<DocxCleanResult> {
  const body = new FormData()
  body.append('file', file)
  body.append('options', JSON.stringify(options))
  const res = await fetch('/api/docx/clean', { method: 'POST', body })
  if (!res.ok) throw new Error(await readError(res))
  return {
    blob: await res.blob(),
    filename: filenameFromDisposition(res.headers.get('content-disposition'), 'cleaned.docx'),
    originalBytes: Number(res.headers.get('x-doc2tex-original-bytes') || file.size),
    cleanedBytes: Number(res.headers.get('x-doc2tex-cleaned-bytes') || 0),
    imagesOptimized: Number(res.headers.get('x-doc2tex-images-optimized') || 0),
  }
}

export async function fetchTemplates(): Promise<TemplateDescriptor[]> {
  const res = await fetch('/api/templates')
  if (!res.ok) throw new Error(await readError(res))
  return res.json() as Promise<TemplateDescriptor[]>
}

export async function generateTemplate(req: TemplateRequest): Promise<GeneratedTemplate> {
  const res = await fetch('/api/templates/generate', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  })
  if (!res.ok) throw new Error(await readError(res))
  return res.json() as Promise<GeneratedTemplate>
}

export async function compileTemplatePdf(req: TemplateRequest): Promise<BinaryDownload> {
  const res = await fetch('/api/templates/compile', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  })
  if (!res.ok) throw new Error(await readError(res))
  return {
    blob: await res.blob(),
    filename: filenameFromDisposition(res.headers.get('content-disposition'), `${req.templateId}.pdf`),
  }
}

export async function extractReferences(file: File | null, text: string): Promise<ExtractionReport> {
  const body = new FormData()
  if (file) body.append('file', file)
  if (text.trim()) body.append('text', text)
  const res = await fetch('/api/references/extract', { method: 'POST', body })
  if (!res.ok) throw new Error(await readError(res))
  return res.json() as Promise<ExtractionReport>
}

export async function runBatchConvert(files: File[], targetFormat: string, toc: boolean): Promise<BatchDownload> {
  const body = new FormData()
  for (const f of files) body.append('files', f)
  body.append('targetFormat', targetFormat)
  body.append('toc', String(toc))
  const res = await fetch('/api/batch/convert', { method: 'POST', body })
  if (!res.ok) throw new Error(await readError(res))
  const summaryRaw = res.headers.get('x-doc2tex-batch-summary')
  const summary = summaryRaw ? (JSON.parse(summaryRaw) as { total: number; succeeded: number; failed: number }) : { total: files.length, succeeded: files.length, failed: 0 }
  return {
    blob: await res.blob(),
    filename: filenameFromDisposition(res.headers.get('content-disposition'), `batch-${targetFormat}.zip`),
    total: summary.total,
    succeeded: summary.succeeded,
    failed: summary.failed,
  }
}
