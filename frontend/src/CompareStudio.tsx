import { useState } from 'react'
import { Broom, GitDiff, WarningCircle, X } from '@phosphor-icons/react'
import { Button } from '@/components/ui/button'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { DocxCleanerTab } from '@/components/DocxCleanerTab'
import { PdfDiffTab } from '@/components/PdfDiffTab'

export default function CompareStudio() {
  const [tab, setTab] = useState<'diff' | 'clean'>('diff')
  const [error, setError] = useState('')
  const [busyMessage, setBusyMessage] = useState('')

  const busy = Boolean(busyMessage)

  return (
    <section className="pdf-studio" aria-label="Compare and Clean">
      <header className="pdf-studio-header">
        <div>
          <h1>Compare &amp; Clean</h1>
          <p>Inspect side-by-side text and visual pixel diffs across two PDFs, or scrub metadata, comments, and oversized media from Word files.</p>
        </div>
      </header>

      {error && (
        <div className="ui-error" role="alert">
          <WarningCircle size={19} />
          <p>{error}</p>
          <Button variant="ghost" size="icon-sm" aria-label="Dismiss Compare and Clean error" onClick={() => setError('')}>
            <X size={16} />
          </Button>
        </div>
      )}

      {busyMessage && (
        <div className="pdf-studio-status" role="status" aria-busy="true">
          <span>{busyMessage}</span>
        </div>
      )}

      <Tabs value={tab} onValueChange={v => setTab(v as typeof tab)} className="pdf-studio-tabs">
        <div className="pdf-tabs-bar">
          <TabsList variant="line" className="ui-tabs-list" aria-label="Compare and Clean tools">
            <TabsTrigger value="diff"><GitDiff size={16} /><span>PDF Visual &amp; Text Diff</span></TabsTrigger>
            <TabsTrigger value="clean"><Broom size={16} /><span>DOCX Pre-Flight Cleaner</span></TabsTrigger>
          </TabsList>
        </div>

        <TabsContent value="diff" className="pdf-tab-panel">
          <PdfDiffTab busy={busy} onBusyChange={setBusyMessage} onError={setError} />
        </TabsContent>

        <TabsContent value="clean" className="pdf-tab-panel">
          <DocxCleanerTab busy={busy} onBusyChange={setBusyMessage} onError={setError} />
        </TabsContent>
      </Tabs>
    </section>
  )
}
