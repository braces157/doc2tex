import { useState } from 'react'
import type { Job, LayoutOptions } from './api'
import { DEFAULT_LAYOUT, jobTitle, savedLayout, validLayout } from './api'
import { Button } from './components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from './components/ui/dialog'

export function LayoutSettings({ layout, onChange, template, onTemplate, job, disabled, canCopy, dirty, onCopy }: {
  layout: LayoutOptions; onChange: (value: LayoutOptions) => void; template: string; onTemplate: (value: string) => void
  job: Job | null; disabled: boolean; canCopy: boolean; dirty: boolean; onCopy: () => Promise<boolean>
}) {
  const [confirming, setConfirming] = useState(false), [copying, setCopying] = useState(false), [error, setError] = useState('')
  const preset = layout.preserveSourceLayout ? 'original'
    : layout.paper === 'a4' && layout.fontSize === 11 && layout.marginMm === 25 && template === 'article' ? 'paper'
      : layout.paper === 'letter' && layout.fontSize === 12 && layout.marginMm === 25.4 && template === 'report' ? 'report' : 'custom'
  const change = <K extends keyof LayoutOptions>(key: K, value: LayoutOptions[K]) => onChange({ ...layout, [key]: value })
  const preserve = () => onChange({ ...layout, preserveSourceLayout: true, marginMm: Number.isFinite(layout.marginMm) ? layout.marginMm : DEFAULT_LAYOUT.marginMm })
  const summary = (value: LayoutOptions) => value.preserveSourceLayout
    ? `Original page size and margins · ${value.fontSize} pt`
    : `${value.paper.toUpperCase()} · ${value.fontSize} pt · ${value.marginMm} mm margins`
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
      <label className="ui-layout-wide ui-layout-original"><input type="checkbox" checked={Boolean(layout.aiLayout)} onChange={event => change('aiLayout', event.target.checked)} />AI layout assistance</label>
      <p className="ui-layout-wide ui-layout-hint">Recognize headings and columns automatically while preserving the original text. If local AI setup is unavailable, conversion continues and the review explains the fallback.</p>
      <label className="ui-layout-wide ui-layout-original"><input type="checkbox" checked={Boolean(layout.preserveSourceLayout)} onChange={event => event.target.checked ? preserve() : change('preserveSourceLayout', false)} />Use original page size and margins</label>
      {layout.preserveSourceLayout && <p className="ui-layout-wide ui-layout-hint">Page size, orientation, and margins come from the Word document. Detected settings and any limitations appear in the conversion review.</p>}
      <label className="ui-layout-wide">Layout preset<select value={preset} onChange={event => {
        if (event.target.value === 'original') preserve()
        if (event.target.value === 'custom') change('preserveSourceLayout', false)
        if (event.target.value === 'paper') { onTemplate('article'); onChange({ ...layout, preserveSourceLayout: false, paper: 'a4', fontSize: 11, marginMm: 25 }) }
        if (event.target.value === 'report') { onTemplate('report'); onChange({ ...layout, preserveSourceLayout: false, paper: 'letter', fontSize: 12, marginMm: 25.4 }) }
      }}><option value="original">Original page layout</option><option value="custom">Custom</option><option value="paper">Generic paper</option><option value="report">Generic report</option></select></label>
      <label>Paper size<select disabled={layout.preserveSourceLayout} value={layout.paper} onChange={event => {
        const paper = event.target.value
        if (paper === 'a4' || paper === 'letter') onChange({ ...layout, preserveSourceLayout: false, paper })
      }}><option value="a4">A4</option><option value="letter">Letter</option></select></label>
      <label>Font size<select value={layout.fontSize} onChange={event => change('fontSize', Number(event.target.value))}>{[10, 11, 12].map(size => <option key={size} value={size}>{size} pt</option>)}</select></label>
      <label className="ui-layout-wide">Margins (mm)<input disabled={layout.preserveSourceLayout} type="number" min={10} max={50} step={0.1} value={Number.isFinite(layout.marginMm) ? layout.marginMm : ''} onChange={event => onChange({ ...layout, preserveSourceLayout: false, marginMm: event.target.valueAsNumber })} /></label>
      <label className="ui-layout-wide">Document title<input maxLength={200} value={layout.title} onChange={event => change('title', event.target.value)} /></label>
      <label className="ui-layout-wide">Author<input maxLength={200} value={layout.author} onChange={event => change('author', event.target.value)} /></label>
      <label className="ui-layout-wide">Date text<input maxLength={80} value={layout.date} placeholder="Leave blank to omit" onChange={event => change('date', event.target.value)} /></label>
    </fieldset>
    {!validLayout(layout) && <p role="alert">Choose margins from 10 to 50 mm with at most one decimal, and valid text fields.</p>}
    {job && <><p>Saved layout: {summary(savedLayout(job.layout))}.</p>
      <p>Saved AI layout assistance: {job.layout?.aiLayout ? 'On' : 'Off'}.</p>
      {dirty && <p>Save your source edits before creating a layout copy.</p>}
      <Button variant="outline" disabled={!canCopy || !validLayout(layout)} onClick={() => { setError(''); setConfirming(true) }}>Create layout copy</Button></>}
    <Dialog open={confirming} onOpenChange={value => { if (!copying) setConfirming(value) }}><DialogContent><DialogHeader><DialogTitle>Create a layout copy</DialogTitle>
      <DialogDescription>The copy starts from the original Word document. Your source edits and saved versions stay in {job ? jobTitle(job) : 'the current project'}; source edits are not carried into the copy.</DialogDescription></DialogHeader>
      <p>{template === 'report' ? 'Report' : 'Article'} · {summary(layout)}.</p>
      <p>AI layout assistance: {layout.aiLayout ? 'On' : 'Off'}.</p>
      {error && <p role="alert">{error}</p>}
      <div className="caption-repair-actions"><Button variant="outline" disabled={copying} onClick={() => setConfirming(false)}>Cancel</Button><Button disabled={copying || !canCopy} onClick={() => void copy()}>{copying ? 'Creating…' : 'Create separate conversion'}</Button></div>
    </DialogContent></Dialog>
  </div>
}
