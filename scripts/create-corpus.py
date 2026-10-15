"""Deterministic DOCX stress fixtures. Run with the Codex bundled Python runtime."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
from io import BytesIO
import json
from docx import Document
from docx.shared import Inches, Pt
from docx.oxml import OxmlElement, parse_xml
from docx.oxml.ns import qn
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1] / 'src/test/resources/corpus'
ROOT.mkdir(parents=True, exist_ok=True)
W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
M = 'http://schemas.openxmlformats.org/officeDocument/2006/math'
R = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
manifest = []

def base(title, summary):
    doc = Document()
    section = doc.sections[0]
    section.top_margin = section.bottom_margin = Inches(.8)
    style = doc.styles['Normal']
    style.font.name = 'Calibri'; style.font.size = Pt(11)
    doc.add_heading(title, 0)
    doc.add_paragraph(summary)
    return doc

def package(doc, filename, edits=None):
    stream = BytesIO(); doc.save(stream)
    with ZipFile(BytesIO(stream.getvalue())) as original:
        files = {name: original.read(name) for name in original.namelist()}
    if edits: edits(files)
    with ZipFile(ROOT / filename, 'w', ZIP_DEFLATED) as output:
        for name, content in files.items(): output.writestr(name, content)

def math(paragraph, body):
    paragraph._p.append(parse_xml(f'<m:oMath xmlns:m="{M}">{body}</m:oMath>'))

def text_run(text): return f'<m:r><m:t>{text}</m:t></m:r>'

doc = base('Research Methods and Evidence', 'A structured stress test for native equations, merged tables, footnotes, nested lists, links, and images.')
doc.add_heading('Experimental design', 1)
doc.add_paragraph('PRESERVE_RESEARCH_START. Cost is $47.35; 23% is uncertain. Literal symbols: & _ # { } ~ ^ \\.')
doc.add_paragraph('Primary observations', 'List Number')
doc.add_paragraph('Nested measurement with emphasis', 'List Bullet 2').add_run(' and a bold conclusion.').bold = True
doc.add_paragraph('Secondary observations', 'List Number')
doc.add_heading('Native equations', 2)
math(doc.add_paragraph(), '<m:f><m:num>'+text_run('a+b')+'</m:num><m:den>'+text_run('c')+'</m:den></m:f>')
math(doc.add_paragraph(), '<m:rad><m:radPr><m:degHide m:val="1"/></m:radPr><m:deg/><m:e>'+text_run('x')+'</m:e></m:rad>')
math(doc.add_paragraph(), '<m:d><m:dPr><m:begChr m:val="("/><m:endChr m:val=")"/></m:dPr><m:e><m:m><m:mr><m:e>'+text_run('1')+'</m:e><m:e>'+text_run('2')+'</m:e></m:mr><m:mr><m:e>'+text_run('3')+'</m:e><m:e>'+text_run('4')+'</m:e></m:mr></m:m></m:e></m:d>')
doc.add_heading('Measured outcomes', 1)
table = doc.add_table(rows=5, cols=3); table.style = 'Table Grid'
for cell, value in zip(table.rows[0].cells, ['Group', 'Mean', 'Spread']): cell.text = value
table.cell(1, 0).merge(table.cell(2, 0)).text = 'Merged vertical group'
table.cell(3, 1).merge(table.cell(3, 2)).text = 'Merged horizontal result'
for row, values in [(1,['','18.25','0.37']),(2,['','19.42','0.28']),(4,['Control','17.63','0.41'])]:
    for col, value in enumerate(values):
        if value: table.cell(row,col).text = value
doc.add_paragraph('PRESERVE_TABLE_END')
image = Image.new('RGB', (640,240), '#f4f6ee'); draw = ImageDraw.Draw(image)
for index, height in enumerate([68,112,90,151,173]): draw.rectangle((55+index*112,210-height,120+index*112,210), fill='#7c965a')
picture = BytesIO(); image.save(picture, format='PNG'); picture.seek(0)
doc.add_picture(picture, width=Inches(4.8))
doc.add_paragraph('Figure 1 Observations across five cohorts', 'Caption')
p = doc.add_paragraph('The note is attached to this sentence')
run = p.add_run(); reference = OxmlElement('w:footnoteReference'); reference.set(qn('w:id'), '1'); run._r.append(reference)
p = doc.add_paragraph('Documentation: ')
link = OxmlElement('w:hyperlink'); link.set(qn('r:id'), doc.part.relate_to('https://pandoc.org/MANUAL.html', R+'/hyperlink', is_external=True))
run = OxmlElement('w:r'); text = OxmlElement('w:t'); text.text = 'Pandoc manual'; run.append(text); link.append(run); p._p.append(link)
doc.add_paragraph('PRESERVE_RESEARCH_FINISH')
def footnotes(files):
    files['word/footnotes.xml'] = f'<w:footnotes xmlns:w="{W}"><w:footnote w:id="1"><w:p><w:r><w:t>PRESERVE_FOOTNOTE. Measurements were repeated independently.</w:t></w:r></w:p></w:footnote></w:footnotes>'.encode()
    files['word/_rels/document.xml.rels'] = files['word/_rels/document.xml.rels'].replace(b'</Relationships>', f'<Relationship Id="rFootnotes" Type="{R}/footnotes" Target="footnotes.xml"/></Relationships>'.encode())
    files['[Content_Types].xml'] = files['[Content_Types].xml'].replace(b'</Types>', b'<Override PartName="/word/footnotes.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footnotes+xml"/></Types>')
package(doc, 'research-complex.docx', footnotes)
manifest.append({'file':'research-complex.docx','mustContain':['PRESERVE_RESEARCH_START','PRESERVE_TABLE_END','PRESERVE_FOOTNOTE','PRESERVE_RESEARCH_FINISH'],'features':['fractions','radicals','matrix','merged tables','nested lists','footnote','hyperlink','PNG figure'],'previewExpected':'clean'})

doc = base('Multilingual Field Notes', 'Checks Unicode preservation separately from the fonts available to the PDF compiler.')
languages = [('French','Café, naïve, œuvre, déjà vu.'),('Vietnamese','Tiếng Việt và những quan sát mới.'),('Greek','Ελληνικά και επιστήμη.'),('Russian','Научные наблюдения и результаты.'),('Thai','ภาษาไทยและการทดลอง'),('Chinese','科学研究与实验结果'),('Japanese','研究と実験の結果'),('Arabic','اللغة العربية ونتائج البحث')]
for language, text in languages:
    doc.add_heading(language, 1); doc.add_paragraph(text)
doc.add_paragraph('PRESERVE_UNICODE_FINISH')
package(doc, 'multilingual.docx')
manifest.append({'file':'multilingual.docx','mustContain':[text for _,text in languages]+['PRESERVE_UNICODE_FINISH'],'features':[language for language,_ in languages],'previewExpected':'font review required'})

doc = base('Edited Operations Report', 'Exposes revision handling, header and footer text, a text box, an embedded chart reference, and comments.')
doc.sections[0].header.paragraphs[0].text = 'HEADER_CONTEXT_NOT_BODY'
doc.sections[0].footer.paragraphs[0].text = 'FOOTER_CONTEXT_NOT_BODY'
p = doc.add_paragraph('Current decision: ')
p._p.append(parse_xml(f'<w:del xmlns:w="{W}" w:id="1" w:author="Review Team" w:date="2026-10-15T00:00:00Z"><w:r><w:delText>REMOVED_OLD_DECISION</w:delText></w:r></w:del>'))
p._p.append(parse_xml(f'<w:ins xmlns:w="{W}" w:id="2" w:author="Review Team" w:date="2026-10-15T00:00:00Z"><w:r><w:t>PRESERVE_ACCEPTED_DECISION</w:t></w:r></w:ins>'))
p = doc.add_paragraph('Text box follows:')
p._p.append(parse_xml(f'<w:r xmlns:w="{W}" xmlns:v="urn:schemas-microsoft-com:vml"><w:pict><v:shape id="Box1" style="width:200pt;height:40pt"><v:textbox><w:txbxContent><w:p><w:r><w:t>TEXTBOX_CONTEXT</w:t></w:r></w:p></w:txbxContent></v:textbox></v:shape></w:pict></w:r>'))
doc.add_paragraph('PRESERVE_OPERATIONS_FINISH')
def extra_parts(files):
    files['word/charts/chart1.xml'] = b'<c:chartSpace xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart"><c:chart/></c:chartSpace>'
    files['word/comments.xml'] = f'<w:comments xmlns:w="{W}"><w:comment w:id="0" w:author="Reviewer"><w:p><w:r><w:t>COMMENT_CONTEXT</w:t></w:r></w:p></w:comment></w:comments>'.encode()
package(doc, 'revisions-layout.docx', extra_parts)
manifest.append({'file':'revisions-layout.docx','mustContain':['PRESERVE_ACCEPTED_DECISION','PRESERVE_OPERATIONS_FINISH'],'mustNotContain':['REMOVED_OLD_DECISION'],'features':['tracked insertions and deletions','header/footer','VML text box','chart part','comments'],'previewExpected':'layout review required'})

doc = base('Longitudinal Observation Register', 'Checks larger documents, deep headings, repeated table headers, long URLs, and the last page of a long conversion.')
for index in range(1,41):
    doc.add_heading(f'Observation {index:03}', 1 if index%4 else 2)
    doc.add_paragraph(f'PRESERVE_SECTION_{index:03}. Each observation records sampling conditions, an independent measurement, and a practical interpretation. The result remains provisional until the next scheduled review. ' * 3)
doc.add_heading('Complete measurement register', 1)
table = doc.add_table(rows=1, cols=4); table.style='Table Grid'
for cell,value in zip(table.rows[0].cells,['Record','Condition','Measurement','Decision']): cell.text=value
repeat = OxmlElement('w:tblHeader'); table.rows[0]._tr.get_or_add_trPr().append(repeat)
for index in range(1,91):
    for cell,value in zip(table.add_row().cells,[f'R{index:03}','Controlled environment',f'{17.31+index*.13:.2f}','Retain for review']): cell.text=value
doc.add_paragraph('PRESERVE_LARGE_FINISH')
package(doc, 'long-register.docx')
manifest.append({'file':'long-register.docx','mustContain':['PRESERVE_SECTION_001','PRESERVE_SECTION_020','PRESERVE_SECTION_040','R090','PRESERVE_LARGE_FINISH'],'features':['40 sections','90-row table','repeated table headers','multi-page output'],'previewExpected':'clean'})
(ROOT/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps([{'file':item['file'],'bytes':(ROOT/item['file']).stat().st_size} for item in manifest],indent=2))
