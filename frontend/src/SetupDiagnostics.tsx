import { useEffect, useEffectEvent, useState } from 'react'
import { api } from './api'
import type { SystemStatus } from './api'
import { Button } from './components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from './components/ui/dialog'

export function SetupDiagnostics({ onClose, onStatus }: { onClose: () => void; onStatus: (status: SystemStatus) => void }) {
  const [attempt, setAttempt] = useState(0), [status, setStatus] = useState<SystemStatus | null>(null), [error, setError] = useState(''), [loading, setLoading] = useState(true)
  const publish = useEffectEvent(onStatus)
  useEffect(() => {
    const controller = new AbortController()
    void api<SystemStatus>('/system', { signal: controller.signal }).then(status => {
      if (!controller.signal.aborted) { setStatus(status); publish(status); setLoading(false) }
    }).catch((error: Error) => { if (!controller.signal.aborted) { setError(error.message); setLoading(false) } })
    return () => controller.abort()
  }, [attempt])
  return <Dialog open onOpenChange={value => { if (!value) onClose() }}><DialogContent className="ui-setup-dialog"><DialogHeader><DialogTitle>Setup diagnostics</DialogTitle>
    <DialogDescription>Check conversion, PDF tools and fonts independently. Setup never uploads your documents.</DialogDescription></DialogHeader>
    {loading && <p role="status">Checking local tools…</p>}{error && <p role="alert">Could not check setup: {error}</p>}
    {status && <div className="ui-setup-content">
      {(status.diagnostics ?? [{ name: 'Pandoc', state: status.pandoc ? 'ready' : 'missing', version: '', guidance: 'Install the document converter and relaunch.' }, { name: 'Tectonic', state: status.tectonic ? 'ready' : 'missing', version: '', guidance: 'Install the PDF compiler to build previews. Source and ZIP export remain available.' }]).map(tool => <section key={tool.name}><h3>{tool.name} · {tool.state}</h3>{tool.version && <p>{tool.version}</p>}<p>{tool.guidance}</p></section>)}
      {status.aiLayout && <section><h3>AI layout assistance · {status.aiLayout.state}</h3><p>{status.aiLayout.guidance}</p></section>}
      <section><h3>Multilingual fonts</h3>{status.missingFonts?.length ? <><p>Missing fonts or licenses: {status.missingFonts.join(', ')}.</p><p>Close the app, install/repair fonts, then relaunch. Unicode source remains available, but PDFs can have missing characters.</p></> : <p>{status.missingFonts ? 'All bundled project fonts and licenses are available.' : 'Font status is unavailable from this server.'}</p>}</section>
      <section><h3>First PDF build and offline use</h3><p>{status.texGuidance ?? 'The first PDF build may download TeX packages. Connect before building; a populated cache does not guarantee every document will compile offline.'}</p></section>
      <section><h3>Install or repair</h3><p>{status.packaged ? 'Close the app with Stop doc2TeX.cmd, then run Install tools.cmd from the extracted app folder. Read its download notice and choose YES to continue. Launch the app again afterward. Projects and settings are retained in your local data folder.' : 'Run scripts/setup-tools.ps1 from a Windows development checkout, or scripts/setup-tools.sh on Linux/macOS, then restart. Read the download requirements in the development guide.'}</p></section>
    </div>}
    <div className="ui-setup-actions"><Button variant="outline" disabled={loading} onClick={() => { setLoading(true); setError(''); setStatus(null); setAttempt(value => value + 1) }}>Check again</Button><Button onClick={onClose}>Close</Button></div>
  </DialogContent></Dialog>
}
