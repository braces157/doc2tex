import { ApiError } from './api'

export async function downloadBibliography(path: string, filename: string) {
  const response = await fetch(`/api${path}`)
  if (!response.ok) {
    const error = await response.json().catch(() => ({})) as { message?: string }
    throw new ApiError(error.message || `Bibliography download failed (${response.status}).`, response.status)
  }
  const blob = await response.blob()
  if (blob.size > 1024 * 1024) throw new Error('Bibliography download exceeds its 1 MiB limit.')
  const url = URL.createObjectURL(blob), link = document.createElement('a')
  link.href = url; link.download = filename; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
}
