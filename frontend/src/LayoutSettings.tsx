import { useState } from 'react'
import type { Job, LayoutOptions } from './api'
import { jobTitle, validLayout } from './api'
import { Button } from './components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from './components/ui/dialog'

export function LayoutSettings({ layout, onChange, template, onTemplate, job, disabled, canCopy, dirty, onCopy }: {
  layout: LayoutOptions; onChange: (value: LayoutOptions) => void; template: string; onTemplate: (value: string) => void
  job: Job | null; disabled: boolean; canCopy: boolean; dirty: boolean; onCopy: () => Promise<boolean>
}) {
  const [confirming, setConfirming] = useState(false), [copying, setCopying] = useState(false), [error, setError] = useState('')
  const preset = layout.paper === 'a4' && layout.fontSize === 11 && layout.marginMm === 25 && template === 'article' ? 'paper'
    : layout.paper === 'letter' && layout.fontSize === 12 && layout.marginMm === 25.4 && template === 'report' ? 'report' : 'custom'
  const change = <K extends keyof LayoutOptions>(key: K, value: LayoutOptions[K]) => onChange({ ...layout, [key]: value })
  const copy = async () => {
    if (!canCopy || copying || !validLayout(layout)) return
    setCopying(true); setError('')
    try { if (await onCopy()) setConfirming(false) }
    catch (error) { setError(error instanceof Error ? error.message : 'Could not create a layout copy.') }
    finally { setCopying(false) }
  }
  return <div className="ui-layout-settings">
    <p>Use these options for a new conversion or a separate copy of an existing document.</p>
    <fieldset disabled={disabled} className="ui-layout-fields"><legend className="sr-only">Document layout options</legend>
      <label className="ui-layout-wide">Layout preset<select value={preset} onChange={event => {
        if (event.target.value === 'paper') { onTemplate('article'); onChange({ ...layout, paper: 'a4', fontSize: 11, marginMm: 25 }) }
        if (event.target.value === 'report') { onTemplate('report'); onChange({ ...layout, paper: 'letter', fontSize: 12, marginMm: 25.4 }) }
      }}><option value="custom">Custom</option><option value="paper">Generic paper</option><option value="report">Generic report</option></select></label>
      <label>Paper size<select value={layout.paper} onChange={event => change('paper', event.target.value as LayoutOptions['paper'])}><option value="a4">A4</option><option value="letter">Letter</option></select></label>
      <label>Font size<select value={layout.fontSize} onChange={event => change('fontSize', Number(event.target.value))}>{[10, 11, 12].map(size => <option key={size} value={size}>{size} pt</option>)}</select></label>
      <label className="ui-layout-wide">Margins (mm)<input type="number" min={10} max={50} step={0.1} value={Number.isFinite(layout.marginMm) ? layout.marginMm : ''} onChange={event => change('marginMm', event.target.valueAsNumber)} /></label>
      <label className="ui-layout-wide">Document title<input maxLength={200} value={layout.title} onChange={event => change('title', event.target.value)} /></label>
      <label className="ui-layout-wide">Author<input maxLength={200} value={layout.author} onChange={event => change('author', event.target.value)} /></label>
      <label className="ui-layout-wide">Date text<input maxLength={80} value={layout.date} placeholder="Leave blank to omit" onChange={event => change('date', event.target.value)} /></label>
    </fieldset>
    {!validLayout(layout) && <p role="alert">Choose margins from 10 to 50 mm with at most one decimal, and valid text fields.</p>}
    {job && <><p>Saved layout: {job.layout?.paper.toUpperCase() ?? 'LETTER'} · {job.layout?.fontSize ?? 10} pt · {job.layout?.marginMm ?? 25.4} mm margins.</p>
      {dirty && <p>Save your source edits before creating a layout copy.</p>}
      <Button variant="outline" disabled={!canCopy || !validLayout(layout)} onClick={() => { setError(''); setConfirming(true) }}>Create layout copy</Button></>}
    <Dialog open={confirming} onOpenChange={value => { if (!copying) setConfirming(value) }}><DialogContent><DialogHeader><DialogTitle>Create a layout copy</DialogTitle>
      <DialogDescription>The copy starts from the original Word document. Your source edits and saved versions stay in {job ? jobTitle(job) : 'the current project'}; source edits are not carried into the copy.</DialogDescription></DialogHeader>
      <p>{template === 'report' ? 'Report' : 'Article'} · {layout.paper.toUpperCase()} · {layout.fontSize} pt · {layout.marginMm} mm margins.</p>
      {error && <p role="alert">{error}</p>}
      <div className="caption-repair-actions"><Button variant="outline" disabled={copying} onClick={() => setConfirming(false)}>Cancel</Button><Button disabled={copying || !canCopy} onClick={() => void copy()}>{copying ? 'Creating…' : 'Create separate conversion'}</Button></div>
    </DialogContent></Dialog>
  </div>
}
