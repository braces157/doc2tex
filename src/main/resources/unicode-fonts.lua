-- Use bundled font families for script runs, preserving Unicode text in the LaTeX source.
local function script(text)
  for _, point in utf8.codes(text) do
    if point >= 0x0E00 and point <= 0x0E7F then return 'DocThai' end
    if (point >= 0x0600 and point <= 0x06FF) or (point >= 0x0750 and point <= 0x077F) then return 'textarabic' end
    if (point >= 0x3040 and point <= 0x30FF) or (point >= 0x3400 and point <= 0x9FFF)
      or (point >= 0xAC00 and point <= 0xD7AF) or (point >= 0xF900 and point <= 0xFAFF) then return 'DocCJK' end
  end
end

local escapes = { ['\\']='\\textbackslash{}', ['{']='\\{', ['}']='\\}', ['#']='\\#',
  ['$']='\\$', ['%']='\\%', ['&']='\\&', ['_']='\\_', ['^']='\\textasciicircum{}', ['~']='\\textasciitilde{}' }
local function escaped(text) return (text:gsub('[\\{}#$%%&_%^~]', escapes)) end

function Inlines(inlines)
  local result = pandoc.List()
  local index = 1
  while index <= #inlines do
    local item = inlines[index]
    local family = item.t == 'Str' and script(item.text) or nil
    if not family then result:insert(item); index = index + 1
    else
      local text = item.text
      index = index + 1
      -- Combine adjacent words in the same script so Arabic gets a whole RTL phrase.
      while index + 1 <= #inlines and inlines[index].t == 'Space'
        and inlines[index+1].t == 'Str' and script(inlines[index+1].text) == family do
        text = text .. ' ' .. inlines[index+1].text
        index = index + 2
      end
      result:insert(pandoc.RawInline('latex', '\\' .. family .. '{' .. escaped(text) .. '}'))
    end
  end
  return result
end
