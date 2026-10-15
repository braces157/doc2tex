export interface Job {
  id: string
  filename: string
  template: string
  status: 'QUEUED' | 'CONVERTING' | 'COMPLETED' | 'FAILED'
  previewStatus: 'NOT_REQUESTED' | 'QUEUED' | 'COMPILING' | 'READY' | 'FAILED'
  message: string
  previewMessage: string
  createdAt: string
  updatedAt: string
  size: number
  revision: number
  displayName?: string
  deletionPending?: boolean
  layout?: LayoutOptions
}

export interface LayoutOptions { paper: 'a4' | 'letter'; fontSize: number; marginMm: number; title: string; author: string; date: string }
export const DEFAULT_LAYOUT: LayoutOptions = { paper: 'letter', fontSize: 10, marginMm: 25.4, title: '', author: '', date: '' }
const safeLayoutText = (value: string) => !Array.from(value).some(character => {
  const point = character.codePointAt(0)!
  return point < 32 || (point >= 127 && point <= 159) || (point >= 0xd800 && point <= 0xdfff)
})
export const validLayout = (layout: LayoutOptions) => ['a4', 'letter'].includes(layout.paper) && [10, 11, 12].includes(layout.fontSize)
  && Number.isFinite(layout.marginMm) && layout.marginMm >= 10 && layout.marginMm <= 50 && Math.abs(layout.marginMm * 10 - Math.round(layout.marginMm * 10)) < 1e-8
  && layout.title.length <= 200 && layout.author.length <= 200 && layout.date.length <= 80 && safeLayoutText(layout.title + layout.author + layout.date)

export interface SystemStatus {
  pandoc: boolean
  tectonic: boolean
  maxUploadBytes: number
  templates: string[]
}

export interface LibraryPage {
  items: Job[]
  page: number
  size: number
  total: number
}

export interface SavedVersion {
  revision: number
  createdAt: string
  bytes: number
  origin: 'converted' | 'legacy' | 'saved' | 'restored'
  restoredFrom: number
}

export interface SavedHistory {
  items: SavedVersion[]
  currentRevision: number
  firstRevision: number
  legacyHistoryUnavailable: boolean
  maxVersions: number
  maxBytes: number
  bytesUsed: number
}

export interface ConversionReview {
  elements: Record<string, number>
  findings: {code: string; severity: 'info' | 'warning'; title: string; detail: string}[]
  compilerWarnings: string[]
  scope: string
  revision?: number
  originalText?: OriginalParagraph[]
  items?: ReviewItem[]
  decisions?: ReviewDecision[]
  originalTruncated?: boolean
  decisionError?: string
}

export interface OriginalParagraph { part: string; location: string; text: string; truncated: boolean }
export interface ReviewItem {
  id: string; code: string; title: string; part: string; location: string; excerpt: string; excerptTruncated: boolean
  mapping: { status: 'matched' | 'unavailable'; line: number | null; reason: string; sourceExcerpt: string }
  fingerprint: string
}
export interface ReviewDecision {
  findingId: string; status: 'reviewed' | 'needs-work'; revision: number; fingerprint: string; updatedAt: string
  applicable: boolean; carried: boolean; applicableRevision: number; reason: string
}

export interface CaptionProposal { id: string; title: string; line: number; start: number; end: number; before: string; after: string }
export interface CaptionRepairPlan { revision: number; sourceHash: string; proposals: CaptionProposal[]; warnings: string[] }

export interface CompilerDiagnostic {
  severity: 'error' | 'warning'
  code: string
  title: string
  explanation: string
  file: string | null
  line: number | null
  raw: string
}

export interface CompilerReport {
  revision: number | null
  status: string
  logTruncated: boolean
  diagnosticsTruncated: boolean
  items: CompilerDiagnostic[]
}

export async function api<T>(path: string, options: RequestInit = {}, text = false): Promise<T> {
  const response = await fetch(`/api${path}`, options)
  if (!response.ok) {
    const error = await response.json().catch(() => ({})) as { message?: string }
    throw new ApiError(error.message || `The request failed (${response.status}). Try again.`, response.status)
  }
  if (response.status === 204) return undefined as T
  return (text ? response.text() : response.json()) as Promise<T>
}

export class ApiError extends Error {
  status: number
  constructor(message: string, status: number) { super(message); this.name = 'ApiError'; this.status = status }
}

export const isMissingDocument = (error: unknown): error is ApiError => error instanceof ApiError && [404, 410].includes(error.status)
export const jobTitle = (job: Job) => job.displayName || job.filename

export const isWorking = (status: string) => ['QUEUED', 'CONVERTING', 'COMPILING'].includes(status)

export function formatBytes(bytes: number) {
  return bytes < 1024 * 1024 ? `${(bytes / 1024).toFixed(1)} KB` : `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

export function downloadText(source: string, filename: string) {
  const url = URL.createObjectURL(new Blob([source], { type: 'text/plain;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url; link.download = filename; link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

export function downloadUrl(url: string, filename: string) {
  const link = document.createElement('a')
  link.href = url; link.download = filename; link.click()
}
