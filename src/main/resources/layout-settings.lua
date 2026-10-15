-- Metadata is made of literal Str/Space nodes: no Markdown or raw TeX is parsed.
local function family(point)
  if point >= 0x0E00 and point <= 0x0E7F then return 'thai' end
  if (point >= 0x0600 and point <= 0x06FF) or (point >= 0x0750 and point <= 0x077F) then return 'arabic' end
  if (point >= 0x3040 and point <= 0x30FF) or (point >= 0x3400 and point <= 0x9FFF)
      or (point >= 0xAC00 and point <= 0xD7AF) or (point >= 0xF900 and point <= 0xFAFF) then return 'cjk' end
  return 'other'
end

local function literal(text)
  local inlines, run, previous = pandoc.Inlines({}), '', nil
  local function flush() if run ~= '' then inlines:insert(pandoc.Str(run)); run = '' end end
  for _, point in utf8.codes(text) do
    if point == 32 then flush(); inlines:insert(pandoc.Space()); previous = nil
    else
      local current = family(point)
      if previous ~= current then flush() end
      run = run .. utf8.char(point); previous = current
    end
  end
  flush(); return pandoc.MetaInlines(inlines)
end

function Meta(meta)
  local file = assert(io.open('layout-settings.json', 'rb'))
  local text = file:read(32769); file:close(); assert(#text <= 32768, 'Layout metadata exceeds its limit')
  local options = pandoc.json.decode(text, false)
  meta.title = literal(options.title)
  if options.author == '' then meta.author = nil else meta.author = pandoc.MetaList({literal(options.author)}) end
  meta.date = literal(options.date)
  return meta
end
