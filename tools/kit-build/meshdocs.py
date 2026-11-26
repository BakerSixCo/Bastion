"""Combine the Meshtastic user documentation (.md/.mdx) into one Markdown file for the kit.

usage: python -I meshdocs.py <docs-repo-root> <out.md> <commit> <commit-date>
"""
import os
import re
import sys

root_dir, out_path, commit, cdate = sys.argv[1:5]
keep = ['docs/about', 'docs/getting-started', 'docs/configuration', 'docs/hardware/antennas',
        'docs/hardware/devices', 'docs/software/android', 'docs/legal']
skip = ['docs/getting-started/serial-drivers']

files = []
for root, dirs, fs in os.walk(os.path.join(root_dir, 'docs')):
    rel = os.path.relpath(root, root_dir).replace(os.sep, '/')
    if not any(rel == k or rel.startswith(k + '/') for k in keep) or any(rel.startswith(s) for s in skip):
        continue
    for f in fs:
        if f.endswith(('.md', '.mdx')):
            files.append(rel + '/' + f)
files.sort()


def clean(t):
    title = None
    m = re.match(r'^---\n(.*?)\n---\n', t, re.S)
    if m:
        tm = re.search(r'^title:\s*(.+)$', m.group(1), re.M)
        title = tm.group(1).strip().strip('"\'') if tm else None
        t = t[m.end():]
    t = re.sub(r'^\s*import .*$', '', t, flags=re.M)
    t = re.sub(r'^\s*export .*$', '', t, flags=re.M)
    t = re.sub(r'<TabItem[^>]*label="([^"]+)"[^>]*>', r'\n**\1**\n', t)
    t = re.sub(r'</?(Tabs|TabItem|details|summary|div|span|center|Admonition|br|img|iframe|video|source|figure|figcaption|a|p|table|thead|tbody|tr|td|th)\b[^>]*/?>', '', t)
    t = re.sub(r'</?[A-Z][A-Za-z.]*\b[^>]*/?>', '', t)                # remaining JSX components
    t = re.sub(r'!\[[^\]]*\]\([^)]*\)', '', t)                        # images
    t = re.sub(r'^:::(\w+)[ \t]*(.*)$', lambda mm: ("**" + mm.group(1).capitalize() + ":** " + mm.group(2)).rstrip(), t, flags=re.M)
    t = re.sub(r'^:::\s*$', '', t, flags=re.M)
    t = re.sub(r'^(#{1,4}) ', lambda mm: '#' * min(len(mm.group(1)) + 2, 6) + ' ', t, flags=re.M)
    return title, re.sub(r'\n{3,}', '\n\n', t).strip()


out = ["# Meshtastic documentation (user guide: overview, getting started, configuration, hardware, Android app)", "",
       "Source: Meshtastic project documentation (https://meshtastic.org/docs/, source repository "
       f"https://github.com/meshtastic/meshtastic, commit {commit[:7]} of {cdate}), downloaded 2026-11-26. Licence: GNU GPL v3.0 "
       "(repository LICENSE). Meshtastic is a registered trademark of Meshtastic LLC. Community documentation, not a government "
       "source. Pages included: about, getting-started (without serial drivers), configuration, hardware/antennas, "
       "hardware/devices, software/android, legal. Developer pages left out. Page text kept; site code, images and layout "
       "tags removed.", ""]
n = 0
for f in files:
    title, body = clean(open(os.path.join(root_dir, f), encoding='utf-8').read())
    if len(body) < 40:
        continue
    out += [f"## {title or os.path.basename(f)} ({f})", "", body, ""]
    n += 1
md = "\n".join(out) + "\n"
open(out_path, 'w', encoding='utf-8', newline='\n').write(md)
print(n, 'pages', len(md), 'chars', len(md.split()), 'words')
