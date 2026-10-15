"""Exercise the running app and produce repeatable evidence and PDF page images.

Run with the bundled Python runtime (pypdf and Pillow required).
"""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.request import Request, urlopen
from io import BytesIO
from zipfile import ZipFile
import json, re, subprocess, time, unicodedata, uuid
from pypdf import PdfReader
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
CORPUS = ROOT/'src/test/resources/corpus'
OUT = ROOT/'.data/corpus-results'
OUT.mkdir(parents=True, exist_ok=True)
BASE = 'http://127.0.0.1:8080/api'

def request(path, method='GET', data=None, headers=None, raw=False):
    with urlopen(Request(BASE+path, data=data, method=method, headers=headers or {}), timeout=30) as response:
        content = response.read()
        return content if raw else json.loads(content)

def wait(job_id, field, terminal, seconds):
    end = time.monotonic()+seconds
    while time.monotonic()<end:
        job = request('/jobs/'+job_id)
        if job[field] in terminal: return job
        time.sleep(.15)
    raise TimeoutError(f'{field} timed out for {job_id}')

def normalize(text):
    return unicodedata.normalize('NFC',text.replace('\\_','_'))

def check(case):
    filename=case['file']; boundary='doc2tex-'+uuid.uuid4().hex
    file=(CORPUS/filename).read_bytes()
    body=(f'--{boundary}\r\nContent-Disposition: form-data; name="template"\r\n\r\narticle\r\n'
          f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\n'
          'Content-Type: application/vnd.openxmlformats-officedocument.wordprocessingml.document\r\n\r\n').encode()+file+f'\r\n--{boundary}--\r\n'.encode()
    started=time.monotonic()
    created=request('/jobs','POST',body,{'Content-Type':f'multipart/form-data; boundary={boundary}'})
    job=wait(created['id'],'status',{'COMPLETED','FAILED'},65)
    convert_seconds=round(time.monotonic()-started,3)
    assert job['status']=='COMPLETED',job['message']
    source=request(f'/jobs/{job["id"]}/source',raw=True).decode()
    missing=[value for value in case['mustContain'] if normalize(value) not in normalize(source)]
    unexpected=[value for value in case.get('mustNotContain',[]) if value in source]
    assert not missing and not unexpected, {'missing':missing,'unexpected':unexpected}
    compile_start=time.monotonic()
    request(f'/jobs/{job["id"]}/preview','POST',b'')
    job=wait(job['id'],'previewStatus',{'READY','FAILED'},310)
    compile_seconds=round(time.monotonic()-compile_start,3)
    assert job['previewStatus']=='READY',job['previewMessage']
    log=request(f'/jobs/{job["id"]}/preview/log',raw=True).decode()
    review=request(f'/jobs/{job["id"]}/review')
    pdf=request(f'/jobs/{job["id"]}/preview',raw=True)
    archive=request(f'/jobs/{job["id"]}/download',raw=True)
    directory=OUT/filename.removesuffix('.docx'); directory.mkdir(exist_ok=True)
    (directory/'main.pdf').write_bytes(pdf); (directory/'main.tex').write_text(source,encoding='utf-8')
    (directory/'review.json').write_text(json.dumps(review,ensure_ascii=False,indent=2),encoding='utf-8')
    with ZipFile(BytesIO(archive)) as zipped:
        assert 'main.tex' in zipped.namelist() and 'conversion-review.json' in zipped.namelist()
        if filename=='multilingual.docx':
            assert 'fonts/NotoSansThai-Regular.ttf' in zipped.namelist()
            assert 'fonts/OFL-Noto.txt' in zipped.namelist()
    reader=PdfReader(BytesIO(pdf))
    extracted='\n'.join(page.extract_text() or '' for page in reader.pages)
    (directory/'pdf-text.txt').write_text(extracted,encoding='utf-8')
    # Text checkpoints exclude math and Unicode extraction quirks; they supplement visual QA.
    checkpoints=[value for value in case['mustContain'] if value.startswith('PRESERVE_') or value=='R090']
    # PDF extraction can insert spaces between kerned glyphs (e.g. "PRESER VE").
    compact_pdf=re.sub(r'\s+','',extracted)
    missing_pdf=[value for value in checkpoints if re.sub(r'\s+','',value) not in compact_pdf]
    missing_glyphs=log.count('Missing character:')
    assert not missing_pdf, f'PDF lost checkpoints: {missing_pdf}'
    assert missing_glyphs==0, 'PDF has missing glyphs'
    result={'file':filename,'jobId':job['id'],'conversionSeconds':convert_seconds,'pdfSeconds':compile_seconds,
            'pdfPages':len(reader.pages),'requiredSourceCheckpoints':len(case['mustContain']),
            'sourceCheckpointsPassed':not missing,'pdfCheckpointsPassed':not missing_pdf,
            'missingGlyphWarnings':missing_glyphs,'findings':[item['code'] for item in review['findings']],
            'compilerWarnings':review['compilerWarnings'],'zipBytes':len(archive)}
    print(json.dumps(result,ensure_ascii=False),flush=True)
    return result

cases=json.loads((CORPUS/'manifest.json').read_text(encoding='utf-8'))
with ThreadPoolExecutor(max_workers=2) as pool:
    results=list(pool.map(check,cases))
(OUT/'results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
for result in results:
    directory=OUT/result['file'].removesuffix('.docx')
    subprocess.run(['pdftoppm','-r','80','-png',str(directory/'main.pdf'),str(directory/'page')],check=True,capture_output=True)
    pages=sorted(directory.glob('page-*.png'))
    columns=min(4,len(pages)); rows=(len(pages)+columns-1)//columns
    montage=Image.new('RGB',(columns*310,rows*430),'#e7eadf'); draw=ImageDraw.Draw(montage)
    for index,path in enumerate(pages):
        with Image.open(path) as page:
            page.thumbnail((294,398))
            x=(index%columns)*310+8; y=(index//columns)*430+22
            montage.paste(page,(x,y)); draw.text((x,y-16),path.stem,fill='#526344')
    montage.save(directory/'contact-sheet.png')
print('Corpus checks passed. PDF contact sheets are in .data/corpus-results.')
