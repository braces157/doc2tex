import type { Draft } from '@/drafts'
import { downloadText } from '@/api'
import { Button } from '@/components/ui/button'

export function DraftRecovery({ draft, revision, onRestore, onDiscard, unavailable = false }: {
  draft: Draft; revision: number | undefined; onRestore: () => void; onDiscard: () => void
  unavailable?: boolean
}) {
  const changed = draft.revision !== revision
  return <section className="draft-recovery" aria-label="Draft recovery">
    <div><strong>{unavailable ? 'This document is no longer available' : changed ? 'Your saved source has changed' : 'An unsaved draft is available'}</strong>
      <p>{unavailable ? 'Download this local draft before discarding it or leaving. Server saves are unavailable.' : changed ? 'This draft belongs to an earlier saved version. Download it to compare with the current source.'
        : 'Restore your local edits, or discard the draft to continue with the saved source.'}</p></div>
    <div className="draft-recovery-actions">
      {!changed && !unavailable && <Button variant="outline" onClick={onRestore}>Restore draft</Button>}
      <Button variant="outline" onClick={() => downloadText(draft.source, 'recovered-draft.tex')}>Download draft</Button>
      <Button variant="ghost" onClick={onDiscard}>Discard draft</Button>
    </div>
  </section>
}
