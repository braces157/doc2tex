-- Operate on caption/field structure only. Plain prose never becomes a reference.
local function load_input()
  local file = assert(io.open('word-reference-input.json', 'rb'))
  local text = file:read(1024 * 1024 + 1); file:close()
  assert(#text <= 1024 * 1024, 'Word reference metadata exceeds its limit')
  return pandoc.json.decode(text, false)
end

local function json_array(items)
  local encoded = {}
  for _, item in ipairs(items) do encoded[#encoded + 1] = pandoc.json.encode(item) end
  return '[' .. table.concat(encoded, ',') .. ']'
end

local function hex(text)
  return (text:gsub('.', function(char) return string.format('%02x', string.byte(char)) end))
end

-- Consume a known ASCII prefix while retaining bookmark spans and their attributes.
local function consume(inlines, remaining)
  local result = pandoc.Inlines({})
  for _, inline in ipairs(inlines) do
    if remaining == 0 then result:insert(inline)
    elseif inline.t == 'Str' then
      local take = math.min(#inline.text, remaining)
      remaining = remaining - take
      if take < #inline.text then inline.text = inline.text:sub(take + 1); result:insert(inline) end
    elseif inline.t == 'Space' or inline.t == 'SoftBreak' then remaining = remaining - 1
    elseif inline.t == 'Span' then
      local content, rest = consume(inline.content, remaining)
      if not content then return nil end
      inline.content = content; remaining = rest; result:insert(inline)
    else return nil end
  end
  return result, remaining
end

function Pandoc(doc)
  if not FORMAT:match('latex') then return doc end
  local input = load_input()
  local warnings, warned, captions = {}, {}, {}
  local used, owners, counts, records, seen = {}, {}, {}, {}, {}
  local figures, tables, converted = 0, 0, 0
  local function warn(text)
    if #warnings < 64 and not warned[text] then warnings[#warnings + 1] = text; warned[text] = true end
  end
  for _, text in ipairs(input.warnings or {}) do warn(text) end
  for _, field in ipairs(input.fields or {}) do if field.id ~= '' then records[field.id] = field end end
  local function reserve(element) if element.identifier and element.identifier ~= '' then used[element.identifier] = true end end
  doc:walk({Span = reserve, Div = reserve, Header = reserve, Figure = reserve, Table = reserve,
    Link = function(link) counts[link.title] = (counts[link.title] or 0) + 1 end})
  local function label_for(kind, aliases, index)
    table.sort(aliases)
    local base = 'doc2tex-' .. kind .. '-' .. (#aliases > 0 and hex(aliases[1]) or 'auto-' .. index)
    local label, suffix = base, 1
    while used[label] do label = base .. '-collision-' .. suffix; suffix = suffix + 1 end
    used[label] = true; return label
  end
  local function caption(element, kind)
    local blocks = element.caption.long
    if #blocks == 0 or pandoc.utils.stringify(blocks):match('^%s*$') then return element end
    local last = blocks[#blocks]
    if last.t ~= 'Para' and last.t ~= 'Plain' then warn('A caption structure requires manual label/numbering review.'); return element end
    local aliases = {}
    blocks:walk({Span = function(span)
      if span.identifier ~= '' and (input.bookmarks or {})[span.identifier] == 1 then aliases[#aliases + 1] = span.identifier end
    end})
    local index
    if kind == 'figure' then figures = figures + 1; index = figures else tables = tables + 1; index = tables end
    local label = label_for(kind, aliases, index)
    local prefix, number = '', ''
    local first = blocks[1]
    if first.t == 'Para' or first.t == 'Plain' then
      local text = pandoc.utils.stringify(first.content)
      local word, digits, punctuation, rest = text:match('^%s*([A-Za-z%.]+)%s+(%d[%d%.]*)([:%)%.]?)%s+(.+)$')
      if word then
        local lowered = word:lower()
        local expected = kind == 'figure' and (lowered == 'figure' or lowered == 'fig.' or lowered == 'fig') or kind == 'table' and lowered == 'table'
        if digits:sub(-1) == '.' then digits = digits:sub(1, -2); punctuation = '.' .. punctuation end
        local numeric = digits:match('^%d[%d%.]*$') and not digits:find('%.%.') and digits:sub(-1) ~= '.'
        if expected and numeric and #digits < 40 then
          local copy = first.content:walk({})
          local remaining = #text - #rest
          local repaired, left = consume(copy, remaining)
          if repaired and left == 0 then
            prefix = '\\DocCaptionNumber{' .. word .. '}{' .. digits .. '}{' .. punctuation .. '}'
            number = digits
            local content = pandoc.Inlines({pandoc.RawInline('latex', prefix), pandoc.Space()})
            content:extend(repaired); first.content = content; blocks[1] = first
          else warn('A numbered caption has formatting that requires manual prefix review.') end
        elseif lowered == 'figure' or lowered == 'table' or lowered == 'fig.' then
          warn('A numbered caption does not match its figure/table ownership; review it manually.')
        end
      end
    end
    last = blocks[#blocks]
    last.content:insert(pandoc.RawInline('latex', '\\label{' .. label .. '}')); blocks[#blocks] = last
    element.caption.long = blocks
    for _, alias in ipairs(aliases) do
      if owners[alias] ~= nil then owners[alias] = false; warn('A bookmark belongs to more than one caption; its references require manual review.')
      else owners[alias] = {label = label, kind = kind} end
    end
    if #captions < 200 then captions[#captions + 1] = {label = label, kind = kind, prefix = prefix, number = number}
    else warn('Only the first 200 caption records are retained for repair review.') end
    return element
  end
  doc = doc:walk({Figure = function(element) return caption(element, 'figure') end,
    Table = function(element) return caption(element, 'table') end})
  doc = doc:walk({Link = function(link)
    local field = records[link.title]
    if not field then return link end
    seen[field.id] = true
    local owner = owners[field.target]
    if counts[field.id] == 1 and link.target == '#' .. field.target and pandoc.utils.stringify(link.content) == field.cached
        and field.supported and owner then
      converted = converted + 1
      return pandoc.RawInline('latex', '\\ref{' .. owner.label .. '}')
    end
    warn(field.reason ~= '' and field.reason or 'A Word reference has no unique caption owner; its cached text needs manual review.')
    return link.content
  end})
  for _, field in ipairs(input.fields or {}) do
    if not field.supported then warn(field.reason)
    elseif not seen[field.id] then warn('A Word REF field was not exposed as a verified link; compare its cached text with the original.') end
  end
  local file = assert(io.open('word-reference-report.json', 'wb'))
  file:write('{"captions":' .. json_array(captions) .. ',"warnings":' .. json_array(warnings) .. ',"convertedReferences":' .. converted .. '}')
  file:close()
  return doc
end
