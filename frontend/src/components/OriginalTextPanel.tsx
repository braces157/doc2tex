import type { OriginalParagraph, ReviewItem } from '@/api'

export function OriginalTextPanel({ item, paragraphs, truncated, onClose }: { item: ReviewItem; paragraphs: OriginalParagraph[]; truncated: boolean; onClose: () => void }) {
  return <aside className="original-text-panel" aria-label="Original Word text comparison">
    <div className="original-text-heading"><h3>Original Word text</h3><button onClick={onClose} aria-label="Close original text comparison">Close</button></div>
    <p>Text and structure only. Word page layout is not reproduced.</p>
    <h4>{item.title}</h4><p className="original-location">{item.part} · {item.location}</p>
    <blockquote>{item.excerpt}</blockquote>{item.excerptTruncated && <p>This excerpt was shortened.</p>}
    <p>{item.mapping.status === 'unavailable' && <strong>Source location unavailable. </strong>}{item.mapping.reason}</p>
    <details><summary>More original text</summary>{paragraphs.map((paragraph, index) => <div className="original-paragraph" key={`${paragraph.part}-${paragraph.location}-${index}`}>
      <p className="original-location">{paragraph.part} · {paragraph.location}</p><p>{paragraph.text}</p>{paragraph.truncated && <small>Paragraph shortened.</small>}
    </div>)}</details>
    {truncated && <p role="status">Original-text inspection was limited; compare important content with Word.</p>}
  </aside>
}
