export interface Draft {
  jobId: string
  revision: number
  source: string
  updatedAt: number
  token: string
}

const PREFIX = 'doc2tex:draft:'
const key = (draft: Pick<Draft, 'jobId' | 'revision'>) => `${PREFIX}${draft.jobId}:${draft.revision}`

export function findDraft(jobId: string, revision: number, savedSource: string): Draft | null {
  const drafts: Draft[] = []
  for (let index = 0; index < localStorage.length; index++) {
    const name = localStorage.key(index)
    if (!name?.startsWith(`${PREFIX}${jobId}:`)) continue
    try {
      const draft = JSON.parse(localStorage.getItem(name) ?? 'null') as Draft | null
      if (draft?.jobId === jobId && Number.isSafeInteger(draft.revision) && draft.revision >= 0
        && typeof draft.source === 'string' && Number.isFinite(draft.updatedAt) && typeof draft.token === 'string'
        && name === key(draft) && draft.source !== savedSource) drafts.push(draft)
    } catch { /* Ignore malformed entries; never prevent opening the saved source. */ }
  }
  return drafts.sort((a, b) => Number(b.revision === revision) - Number(a.revision === revision)
    || b.updatedAt - a.updatedAt)[0] ?? null
}

export function writeDraft(jobId: string, revision: number, source: string): Draft {
  const draft = { jobId, revision, source, updatedAt: Date.now(), token: crypto.randomUUID() }
  localStorage.setItem(key(draft), JSON.stringify(draft))
  return draft
}

export function removeDraft(draft: Draft) {
  // A second window may have replaced this draft since it was read.
  const current = localStorage.getItem(key(draft))
  if (current && (JSON.parse(current) as Draft).token === draft.token) localStorage.removeItem(key(draft))
}
