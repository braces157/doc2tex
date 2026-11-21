import { ArrowDown, Check, Copy, FileText, Images } from '@phosphor-icons/react'
import { Button } from '@/components/ui/button'
import { downloadBlob, type ExtractOutput, type LoadedPdf } from '@/pdfApi'

interface PdfExtractTabProps {
  docs: LoadedPdf[]
  activeDoc: LoadedPdf | null
  selectedDocUid: string
  setSelectedDocUid: (uid: string) => void
  extractResult: ExtractOutput | null
  copiedExtract: boolean
  busy: boolean
  onExtractTextOrMarkdown: (kind: 'text' | 'markdown') => void
  onExtractImages: () => void
  onCopyExtract: () => void
}

export function PdfExtractTab({
  docs,
  activeDoc,
  selectedDocUid,
  setSelectedDocUid,
  extractResult,
  copiedExtract,
  busy,
  onExtractTextOrMarkdown,
  onExtractImages,
  onCopyExtract,
}: PdfExtractTabProps) {
  return (
    <>
      {docs.length > 1 && (
        <div className="pdf-doc-selector">
          <label htmlFor="extract-doc-select">Document to extract from</label>
          <select
            id="extract-doc-select"
            value={activeDoc?.uid ?? selectedDocUid}
            onChange={event => setSelectedDocUid(event.target.value)}
          >
            {docs.map(doc => (
              <option key={doc.uid} value={doc.uid}>{doc.file.name}</option>
            ))}
          </select>
        </div>
      )}

      <div className="pdf-extract-actions">
        <div>
          <h2>Extract content</h2>
          <p>Pull selectable text, clean page-sectioned Markdown, or embedded raster images without leaving this machine.</p>
        </div>
        <div className="pdf-extract-buttons">
          <Button variant="outline" disabled={busy || !activeDoc} onClick={() => onExtractTextOrMarkdown('text')}>
            <FileText size={15} />
            Extract plain text
          </Button>
          <Button variant="outline" disabled={busy || !activeDoc} onClick={() => onExtractTextOrMarkdown('markdown')}>
            <FileText size={15} />
            Extract Markdown
          </Button>
          <Button className="pdf-primary-cta" disabled={busy || !activeDoc} onClick={onExtractImages}>
            <Images size={15} weight="bold" />
            Download embedded images (.zip)
            {activeDoc?.inspection ? ` (${activeDoc.inspection.embeddedImageCount})` : ''}
          </Button>
        </div>
      </div>

      {extractResult && extractResult.docUid === activeDoc?.uid ? (
        <div className="pdf-extract-output">
          <div className="pdf-extract-bar">
            <span>
              {extractResult.kind === 'markdown' ? 'Markdown' : 'Plain text'} · {extractResult.pageCount} pages ·{' '}
              {extractResult.totalChars.toLocaleString()} chars
              {extractResult.truncated ? ' (truncated at limit)' : ''}
            </span>
            <div>
              <Button variant="outline" size="sm" onClick={onCopyExtract}>
                {copiedExtract ? <Check size={14} /> : <Copy size={14} />}
                {copiedExtract ? 'Copied' : 'Copy'}
              </Button>
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  const ext = extractResult.kind === 'markdown' ? '.md' : '.txt'
                  const base = activeDoc.file.name.replace(/\.pdf$/i, '') || 'extracted'
                  downloadBlob(new Blob([extractResult.content], { type: 'text/plain;charset=utf-8' }), `${base}${ext}`)
                }}
              >
                <ArrowDown size={14} />
                Download {extractResult.kind === 'markdown' ? '.md' : '.txt'}
              </Button>
            </div>
          </div>
          <textarea
            readOnly
            aria-label="Extracted PDF content"
            value={extractResult.content}
            className="pdf-extract-textarea"
          />
        </div>
      ) : (
        <div className="pdf-empty-pages">
          <p>Choose an extraction mode above to inspect or download text, Markdown, or embedded PNG assets.</p>
        </div>
      )}
    </>
  )
}
