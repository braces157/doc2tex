import { useEffect, useState } from 'react'
import { ArrowsLeftRight, BookOpen, Stack, WarningCircle, X } from '@phosphor-icons/react'
import { Button } from '@/components/ui/button'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { BatchConvertTab } from '@/components/BatchConvertTab'
import { MultiFormatTab } from '@/components/MultiFormatTab'
import { TemplatesAndRefsTab } from '@/components/TemplatesAndRefsTab'
import { fetchFormatCatalog, type FormatCatalog } from '@/ecosystemApi'

export default function FormatHub({ tectonicReady }: { tectonicReady: boolean }) {
  const [tab, setTab] = useState<'convert' | 'batch' | 'templates'>('convert')
  const [catalog, setCatalog] = useState<FormatCatalog | null>(null)
  const [error, setError] = useState('')
  const [busyMessage, setBusyMessage] = useState('')

  useEffect(() => {
    void fetchFormatCatalog().then(setCatalog).catch(() => { /* allow UI fallback */ })
  }, [])

  const busy = Boolean(busyMessage)

  return (
    <section className="pdf-studio" aria-label="Format Hub">
      <header className="pdf-studio-header">
        <div>
          <h1>Format Hub</h1>
          <p>Convert across Markdown, Word, HTML, EPUB, ODT, RTF, LaTeX, Beamer, PowerPoint, and Typst — plus batch queues and academic starters.</p>
        </div>
      </header>

      {error && (
        <div className="ui-error" role="alert">
          <WarningCircle size={19} />
          <p>{error}</p>
          <Button variant="ghost" size="icon-sm" aria-label="Dismiss Format Hub error" onClick={() => setError('')}>
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
          <TabsList variant="line" className="ui-tabs-list" aria-label="Format Hub tools">
            <TabsTrigger value="convert"><ArrowsLeftRight size={16} /><span>Universal Converter</span></TabsTrigger>
            <TabsTrigger value="batch"><Stack size={16} /><span>Batch Queue</span></TabsTrigger>
            <TabsTrigger value="templates"><BookOpen size={16} /><span>Templates &amp; Citations</span></TabsTrigger>
          </TabsList>
        </div>

        <TabsContent value="convert" className="pdf-tab-panel">
          <MultiFormatTab catalog={catalog} busy={busy} onBusyChange={setBusyMessage} onError={setError} />
        </TabsContent>

        <TabsContent value="batch" className="pdf-tab-panel">
          <BatchConvertTab catalog={catalog} busy={busy} onBusyChange={setBusyMessage} onError={setError} />
        </TabsContent>

        <TabsContent value="templates" className="pdf-tab-panel">
          <TemplatesAndRefsTab busy={busy} tectonicReady={tectonicReady} onBusyChange={setBusyMessage} onError={setError} />
        </TabsContent>
      </Tabs>
    </section>
  )
}
