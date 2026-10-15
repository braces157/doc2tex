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
}

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

export interface ConversionReview {
  elements: Record<string, number>
  findings: {code: string; severity: 'info' | 'warning'; title: string; detail: string}[]
  compilerWarnings: string[]
  scope: string
}

export async function api<T>(path: string, options: RequestInit = {}, text = false): Promise<T> {
  const response = await fetch(`/api${path}`, options)
  if (!response.ok) {
    const error = await response.json().catch(() => ({})) as { message?: string }
    throw new Error(error.message || `The request failed (${response.status}). Try again.`)
  }
  return (text ? response.text() : response.json()) as Promise<T>
}

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
