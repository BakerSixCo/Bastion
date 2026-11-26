"""Convert an eCFR "full" XML part to Markdown, keeping only the listed subparts (all when none given).

usage: python -I ecfr2md.py in.xml out.md "Title line" "Source line" [SUBPART_LETTER ...]
Text is kept as published: headings, paragraphs, notes and tables (GPOTABLE -> Markdown table).
"""
import re
import sys
import xml.etree.ElementTree as ET

src, out, title, source = sys.argv[1:5]
keep = set(sys.argv[5:])


def text(el):
    s = "".join(el.itertext())
    return re.sub(r"\s+", " ", s).strip()


def table(el, lines):
    rows = []
    heads = [text(h) for h in el.iter("CHED")]
    for row in el.iter("ROW"):
        rows.append([text(c) for c in row.findall("ENT")])
    ttl = el.find("TTITLE")
    if ttl is not None and text(ttl):
        lines.append(f"**{text(ttl)}**")
        lines.append("")
    width = max([len(heads)] + [len(r) for r in rows]) if (heads or rows) else 0
    if width == 0:
        return
    heads = (heads + [""] * width)[:width] if heads else [""] * width
    lines.append("| " + " | ".join(h.replace("|", "/") for h in heads) + " |")
    lines.append("|" + " --- |" * width)
    for r in rows:
        r = (r + [""] * width)[:width]
        lines.append("| " + " | ".join(c.replace("|", "/") for c in r) + " |")
    lines.append("")
    for tn in el.iter("TNOTE"):
        lines.append(text(tn))
        lines.append("")


def html_table(el, lines):
    rows = []
    for tr in el.iter("TR"):
        rows.append([text(c) for c in tr if c.tag in ("TH", "TD")])
    rows = [r for r in rows if r]
    if not rows:
        return
    width = max(len(r) for r in rows)
    rows = [(r + [""] * width)[:width] for r in rows]
    lines.append("| " + " | ".join(c.replace("|", "/") for c in rows[0]) + " |")
    lines.append("|" + " --- |" * width)
    for r in rows[1:]:
        lines.append("| " + " | ".join(c.replace("|", "/") for c in r) + " |")
    lines.append("")


def walk(el, lines, depth):
    tag = el.tag
    if tag == "TABLE":
        html_table(el, lines)
        return
    if tag == "DIV6":  # subpart
        letter = el.get("N", "")
        if keep and letter not in keep:
            return
        head = el.find("HEAD")
        lines.append(f"## {text(head) if head is not None else 'Subpart ' + letter}")
        lines.append("")
        for c in el:
            if c.tag != "HEAD":
                walk(c, lines, depth + 1)
        return
    if tag == "DIV7":  # subject group
        head = el.find("HEAD")
        if head is not None:
            lines.append(f"### {text(head)}")
            lines.append("")
        for c in el:
            if c.tag != "HEAD":
                walk(c, lines, depth + 1)
        return
    if tag == "DIV8":  # section
        head = el.find("HEAD")
        lines.append(f"#### {text(head) if head is not None else el.get('N', '')}")
        lines.append("")
        for c in el:
            if c.tag != "HEAD":
                walk(c, lines, depth + 1)
        return
    if tag in ("P", "FP", "NOTE", "EXTRACT", "FTNT"):
        t = text(el)
        if t:
            lines.append(t)
            lines.append("")
        for t2 in el.iter("GPOTABLE"):
            table(t2, lines)
        return
    if tag == "GPOTABLE":
        table(el, lines)
        return
    if tag in ("CITA", "SECAUTH", "EDNOTE", "AUTH", "SOURCE", "HEAD"):
        return
    for c in el:
        walk(c, lines, depth)


root = ET.parse(src).getroot()
lines = [f"# {title}", "", source, ""]
walk(root, lines, 0)
md = re.sub(r"\n{3,}", "\n\n", "\n".join(lines)).strip() + "\n"
open(out, "w", encoding="utf-8", newline="\n").write(md)
print(out, len(md), "chars")
