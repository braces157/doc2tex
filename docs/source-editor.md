# Editing your LaTeX source

After converting or reopening a document, open **Source**. Change ordinary words directly in `main.tex`. LaTeX commands such as `\section{Introduction}` describe formatting; keep their backslashes and paired braces when editing the text inside them.

## Make common changes

- Select words and choose **Bold** or **Italic**. The selected words stay selected inside the new command. Type to replace them, or press an arrow key to keep editing nearby.
- **Insert** adds section headings, subsection headings, bulleted or numbered lists, inline or display equations, and footnotes. With no selection, it selects placeholder text for you to replace. Add another `\item` inside a list for each new item.
- Start typing a common command, such as `\sec`, to see suggestions with plain-language descriptions. Choose with the arrow keys and Enter, or click a suggestion. Escape dismisses suggestions. Tab moves focus to the next control.
- Ordinary braces, brackets, and parentheses close automatically. Escaped LaTeX delimiters such as `\{` and `\(` stay as typed. Matching pairs highlight near the cursor.
- **Toggle comment** adds or removes `%` at the start of selected lines. LaTeX ignores commented text when building a PDF.
- **Find / replace** locates words or commands. **Line / Go** jumps to a line number. **Wrap** fits long lines into the available width without changing the source.

Open **Outline** to jump between conventional single-line part, chapter, section, subsection, and subsubsection headings. It updates as you edit, handles nested braces in titles, and skips commented headings and common literal-code environments. It shows up to 200 headings; multiline headings and headings produced by custom macros are not interpreted.

Use **Expand editor** beside the output tabs for more space. **Exit expanded editor** brings back the document settings. Saving, PDF building, diagnostics, and saved versions remain available in this view. **Editing guide** opens help inside the editor.

## Save and check your changes

Choose **Save changes** to save the current source. Browser-local draft recovery also keeps unfinished edits between visits; a draft is separate from a saved version.

Choose **Build PDF** above the source to save and build a fresh preview. If a build fails, read the compiler diagnostics below the editor and use its source-line button. After further edits, save and rebuild before using old diagnostic locations. **Saved versions** can recover an earlier save.

| Action | Windows / Linux | macOS |
| --- | --- | --- |
| Save | Ctrl S | Cmd S |
| Save and build PDF | Ctrl Enter | Cmd Enter |
| Undo / redo | Ctrl Z / Ctrl Shift Z | Cmd Z / Cmd Shift Z |
| Bold / italic | Ctrl B / Ctrl I | Cmd B / Cmd I |
| Toggle comment | Ctrl / | Cmd / |
| Indent / outdent | Ctrl ] / Ctrl [ | Cmd ] / Cmd [ |

The source becomes read-only while saving, building, or restoring a document. PDF building requires Tectonic; setup diagnostics explain how to install it.
