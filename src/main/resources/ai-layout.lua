local function normalize(text)
  return text:gsub(utf8.char(0xA0), ' '):gsub('%s+', ' '):gsub('^ ', ''):gsub(' $', '')
end

function Pandoc(doc)
  local file = assert(io.open('ai-layout-plan.json', 'rb'))
  local text = file:read(131073); file:close()
  assert(#text <= 131072, 'AI heading plan exceeds its limit')
  local plan = pandoc.json.decode(text, false)
  local targets, columns, counts, applied, applied_columns = {}, {}, {}, {}, {}
  for _, heading in ipairs(plan.headings) do targets[normalize(heading.text)] = heading.text end
  for _, text in ipairs(plan.columnStarts or {}) do columns[normalize(text)] = text end
  local function count(block)
    local key = normalize(pandoc.utils.stringify(block))
    counts[key] = (counts[key] or 0) + 1
  end
  doc:walk({Para = count, Plain = count, Header = count})
  local blocks = pandoc.Blocks({})
  for _, block in ipairs(doc.blocks) do
    local key = normalize(pandoc.utils.stringify(block))
    if (block.t == 'Para' or block.t == 'Header') and columns[key] and counts[key] == 1 then
      blocks:insert(pandoc.RawBlock('latex', '\\newpage'))
      applied_columns[#applied_columns + 1] = pandoc.json.encode(columns[key])
    end
    if block.t == 'Para' then
      if targets[key] and counts[key] == 1 then
        block = pandoc.Header(1, block.content)
        applied[#applied + 1] = pandoc.json.encode(targets[key])
      end
    end
    blocks:insert(block)
  end
  doc.blocks = blocks
  local output = assert(io.open('ai-layout-applied.json', 'wb'))
  output:write('{"headings":[' .. table.concat(applied, ',') .. '],"columnBreaks":[' .. table.concat(applied_columns, ',') .. ']}'); output:close()
  return doc
end
