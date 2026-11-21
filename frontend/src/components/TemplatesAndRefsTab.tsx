import { useEffect, useRef, useState } from 'react'
import { ArrowDown, BookOpen, Check, Copy, FilePdf, FileText, Sparkle } from '@phosphor-icons/react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  compileTemplatePdf, extractReferences, fetchTemplates, generateTemplate,
  type ExtractionReport, type TemplateDescriptor,
} from '@/ecosystemApi'
import { downloadBlob } from '@/pdfApi'

interface TemplatesAndRefsTabProps {
  busy: boolean
  tectonicReady: boolean
  onBusyChange: (msg: string) => void
  onError: (msg: string) => void
}

export function TemplatesAndRefsTab({ busy, tectonicReady, onBusyChange, onError }: TemplatesAndRefsTabProps) {
  const refFileInput = useRef<HTMLInputElement>(null)
  const [templates, setTemplates] = useState<TemplateDescriptor[]>([])
  const [templateId, setTemplateId] = useState('ieee-twocol')
  const [title, setTitle] = useState('Reproducible Local Publishing')
  const [author, setAuthor] = useState('Jane Doe')
  const [affiliation, setAffiliation] = useState('Department of Computer Science')
  const [abstractText, setAbstractText] = useState('Concise summary of problem, approach, and empirical verification.')
  const [date, setDate] = useState('November 2026')
  const [paper, setPaper] = useState<'a4' | 'letter'>('letter')
  const [fontSize, setFontSize] = useState(10)
  const [generatedSource, setGeneratedSource] = useState('')
  const [generatedName, setGeneratedName] = useState('ieee-twocol.tex')
  const [copiedTpl, setCopiedTpl] = useState(false)

  const [refFile, setRefFile] = useState<File | null>(null)
  const [refText, setRefText] = useState('')
  const [refReport, setRefReport] = useState<ExtractionReport | null>(null)
  const [copiedBib, setCopiedBib] = useState(false)
  const refGenRef = useRef(0)

  useEffect(() => {
    void fetchTemplates().then(list => setTemplates(list)).catch(() => { /* fallback handled by UI */ })
  }, [])

  const selectTemplate = (id: string) => {
    setTemplateId(id)
    const found = templates.find(item => item.id === id)
    if (found) {
      setPaper(found.defaultPaper)
      setFontSize(found.defaultFontSize)
    }
  }

  const runGenerate = async () => {
    onError('')
    onBusyChange('Generating LaTeX template…')
    try {
      const res = await generateTemplate({
        templateId, title, author, affiliation, abstractText, date, paper, fontSize,
      })
      setGeneratedSource(res.source)
      setGeneratedName(res.filename)
      toast.success(`Generated ${res.filename}`)
    } catch (failure) {
      onError(failure instanceof Error ? failure.message : 'Could not generate template.')
    } finally {
      onBusyChange('')
    }
  }

  const runCompile = async () => {
    onError('')
    onBusyChange('Compiling academic template PDF…')
    try {
      const pdf = await compileTemplatePdf({
        templateId, title, author, affiliation, abstractText, date, paper, fontSize,
      })
      downloadBlob(pdf.blob, pdf.filename)
      toast.success(`Compiled ${pdf.filename}`)
    } catch (failure) {
      onError(failure instanceof Error ? failure.message : 'Could not compile template.')
    } finally {
      onBusyChange('')
    }
  }

  const updateRefFile = (next: File | null) => {
    refGenRef.current += 1
    setRefFile(next)
    setRefReport(null)
  }

  const updateRefText = (next: string) => {
    refGenRef.current += 1
    setRefText(next)
    setRefReport(null)
  }

  const runExtractRefs = async () => {
    const gen = ++refGenRef.current
    onError('')
    onBusyChange('Scanning text for DOIs, arXiv IDs, ISBNs, and references…')
    try {
      const report = await extractReferences(refFile, refText)
      if (refGenRef.current !== gen) return
      setRefReport(report)
      toast.success(`Found ${report.totalFound} citation candidate${report.totalFound === 1 ? '' : 's'}`)
    } catch (failure) {
      if (refGenRef.current !== gen) return
      onError(failure instanceof Error ? failure.message : 'Reference extraction failed.')
    } finally {
      if (refGenRef.current === gen) onBusyChange('')
    }
  }

  const copyText = async (text: string, which: 'tpl' | 'bib') => {
    try {
      await navigator.clipboard.writeText(text)
      if (which === 'tpl') {
        setCopiedTpl(true)
        setTimeout(() => setCopiedTpl(false), 1800)
      } else {
        setCopiedBib(true)
        setTimeout(() => setCopiedBib(false), 1800)
      }
      toast.success('Copied to clipboard')
    } catch {
      onError('Clipboard access is unavailable.')
    }
  }

  return (
    <div className="eco-two-col eco-templates-grid">
      <section className="eco-card-block" aria-label="Academic template studio">
        <div className="pdf-section-title">
          <h2>Academic Template Studio</h2>
          <BookOpen size={17} />
        </div>
        <p className="pdf-privacy-copy">Generate verified, Tectonic-ready LaTeX starters with escaped literal metadata.</p>
        <div className="pdf-field-group">
          <label htmlFor="tpl-select">Template preset</label>
          <select
            id="tpl-select"
            className="eco-select"
            value={templateId}
            disabled={busy}
            onChange={event => selectTemplate(event.target.value)}
          >
            {(templates.length > 0 ? templates : [
              { id: 'ieee-twocol', name: 'IEEE-Style Conference (2-col)', category: 'Conference', description: '', defaultPaper: 'letter', defaultFontSize: 10, twoColumn: true },
              { id: 'acm-manuscript', name: 'ACM-Style Manuscript', category: 'Journal', description: '', defaultPaper: 'letter', defaultFontSize: 11, twoColumn: false },
              { id: 'lncs-paper', name: 'LNCS-Style Proceedings', category: 'Conference', description: '', defaultPaper: 'a4', defaultFontSize: 10, twoColumn: false },
              { id: 'beamer-slides', name: 'Beamer Presentation Slides', category: 'Slides', description: '', defaultPaper: 'a4', defaultFontSize: 11, twoColumn: false },
              { id: 'thesis-book', name: 'Thesis & Dissertation', category: 'Long-form', description: '', defaultPaper: 'a4', defaultFontSize: 11, twoColumn: false },
              { id: 'academic-cv', name: 'Academic CV & Resume', category: 'Career', description: '', defaultPaper: 'letter', defaultFontSize: 10, twoColumn: false },
            ]).map(item => (
              <option key={item.id} value={item.id}>{item.name}</option>
            ))}
          </select>
        </div>

        <div className="eco-field-pair">
          <div className="pdf-field-group">
            <label htmlFor="tpl-title">Document title</label>
            <input id="tpl-title" type="text" maxLength={200} value={title} onChange={e => setTitle(e.target.value)} />
          </div>
          <div className="pdf-field-group">
            <label htmlFor="tpl-author">Author(s)</label>
            <input id="tpl-author" type="text" maxLength={200} value={author} onChange={e => setAuthor(e.target.value)} />
          </div>
        </div>

        <div className="eco-field-pair">
          <div className="pdf-field-group">
            <label htmlFor="tpl-affil">Affiliation / Subtitle</label>
            <input id="tpl-affil" type="text" maxLength={200} value={affiliation} onChange={e => setAffiliation(e.target.value)} />
          </div>
          <div className="pdf-field-group">
            <label htmlFor="tpl-date">Date</label>
            <input id="tpl-date" type="text" maxLength={80} value={date} onChange={e => setDate(e.target.value)} />
          </div>
        </div>

        <div className="eco-field-pair">
          <div className="pdf-field-group">
            <label htmlFor="tpl-paper">Paper size</label>
            <select id="tpl-paper" className="eco-select" value={paper} onChange={e => setPaper(e.target.value as 'a4' | 'letter')}>
              <option value="letter">US Letter</option>
              <option value="a4">A4</option>
            </select>
          </div>
          <div className="pdf-field-group">
            <label htmlFor="tpl-fontsize">Font size</label>
            <select id="tpl-fontsize" className="eco-select" value={fontSize} onChange={e => setFontSize(Number(e.target.value))}>
              <option value={10}>10 pt</option>
              <option value={11}>11 pt</option>
              <option value={12}>12 pt</option>
            </select>
          </div>
        </div>

        <div className="pdf-field-group">
          <label htmlFor="tpl-abstract">Abstract / Overview</label>
          <textarea
            id="tpl-abstract"
            rows={3}
            className="eco-small-textarea"
            maxLength={2000}
            value={abstractText}
            onChange={e => setAbstractText(e.target.value)}
          />
        </div>

        <div className="eco-btn-group">
          <Button variant="outline" disabled={busy} onClick={() => void runGenerate()}>
            <Sparkle size={15} />
            Generate .tex
          </Button>
          <Button className="pdf-primary-cta" disabled={busy || !tectonicReady} onClick={() => void runCompile()}>
            <FilePdf size={15} weight="bold" />
            Compile PDF
          </Button>
        </div>

        {generatedSource && (
          <div className="pdf-extract-output">
            <div className="pdf-extract-bar">
              <span>{generatedName}</span>
              <div className="eco-btn-group">
                <Button variant="outline" size="sm" onClick={() => void copyText(generatedSource, 'tpl')}>
                  {copiedTpl ? <Check size={14} /> : <Copy size={14} />}
                  {copiedTpl ? 'Copied' : 'Copy'}
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => downloadBlob(new Blob([generatedSource], { type: 'text/plain;charset=utf-8' }), generatedName)}
                >
                  <ArrowDown size={14} />
                  Download .tex
                </Button>
              </div>
            </div>
            <textarea readOnly aria-label="Generated template LaTeX" value={generatedSource} className="eco-code-preview" />
          </div>
        )}
      </section>

      <section className="eco-card-block" aria-label="Offline reference extractor">
        <input
          ref={refFileInput}
          type="file"
          className="sr-only"
          aria-label="Upload paper for reference extraction"
          accept=".pdf,.docx,.txt,.md,.tex,.bib"
          onChange={event => {
            updateRefFile(event.target.files?.[0] ?? null)
            event.target.value = ''
          }}
        />
        <div className="pdf-section-title">
          <h2>DOI &amp; Reference → BibTeX</h2>
          <FileText size={17} />
        </div>
        <p className="pdf-privacy-copy">
          Scan PDFs, DOCX files, or pasted bibliography text offline for DOIs, arXiv IDs, ISBNs, and numbered citations to emit importable <code>.bib</code> entries.
        </p>

        <div className="eco-btn-group">
          <Button variant="outline" size="sm" disabled={busy} onClick={() => refFileInput.current?.click()}>
            {refFile ? `File: ${refFile.name}` : 'Attach PDF / DOCX / TXT'}
          </Button>
          {refFile && (
            <Button variant="ghost" size="sm" onClick={() => updateRefFile(null)}>Clear file</Button>
          )}
        </div>

        <div className="pdf-field-group">
          <label htmlFor="ref-paste-input">Or paste reference text</label>
          <textarea
            id="ref-paste-input"
            rows={4}
            className="eco-small-textarea"
            placeholder="Paste DOIs (e.g. 10.1145/...), arXiv:2401.12345, ISBNs, or numbered references..."
            value={refText}
            onChange={e => updateRefText(e.target.value)}
          />
        </div>

        <Button
          className="pdf-primary-cta"
          disabled={busy || (!refFile && !refText.trim())}
          onClick={() => void runExtractRefs()}
        >
          Extract .bib entries
        </Button>

        {refReport && (
          <div className="pdf-extract-output">
            <div className="pdf-extract-bar">
              <span>{refReport.totalFound} BibTeX entr{refReport.totalFound === 1 ? 'y' : 'ies'} found</span>
              {refReport.bibtex && (
                <div className="eco-btn-group">
                  <Button variant="outline" size="sm" onClick={() => void copyText(refReport.bibtex, 'bib')}>
                    {copiedBib ? <Check size={14} /> : <Copy size={14} />}
                    {copiedBib ? 'Copied' : 'Copy .bib'}
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => downloadBlob(new Blob([refReport.bibtex], { type: 'text/plain;charset=utf-8' }), 'extracted-references.bib')}
                  >
                    <ArrowDown size={14} />
                    Download .bib
                  </Button>
                </div>
              )}
            </div>
            {refReport.bibtex && refReport.warnings.length > 0 && (
              <p className="eco-hint" role="status">{refReport.warnings.join(' ')}</p>
            )}
            <textarea
              readOnly
              aria-label="Extracted BibTeX output"
              value={refReport.bibtex || refReport.warnings.join('\n')}
              className="eco-code-preview"
            />
          </div>
        )}
      </section>
    </div>
  )
}
