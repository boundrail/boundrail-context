---
name: markdown-conventions
description: Formatting rules for Markdown files in this repo (docs/, AGENTS.md, skills). Use when writing or editing any .md file.
---

# Markdown conventions

## Line width: 150 characters at most

Every line SHALL be at most 150 characters long.

- Wrap at word boundaries. Never break inside inline code, a link, or an ID such as `POL-09`.
- Indent continuation lines of a list item to align with the item's text.
- A blank line separates paragraphs; a single line break inside a paragraph is only for wrapping.

Exceptions, because wrapping would break rendering:

- table rows;
- lines inside fenced code blocks;
- a line that is a single URL or link longer than 150 characters;
- YAML front matter values.

When editing an existing file, wrap only the lines you write or change. Rewrap a whole file only when asked.

Check a file:

```bash
python3 -c 'import sys; [print(f"{f}:{i}: {len(l)}") for f in sys.argv[1:] for i, l in enumerate(open(f, encoding="utf-8"), 1)
  if len(l.rstrip("\n")) > 150 and not l.startswith("|")]' <file>.md
```

The check counts characters (plain `awk` counts bytes, so `–` or `≥` would inflate lengths). It skips table rows but not code blocks or front
matter; review those hits by hand.
