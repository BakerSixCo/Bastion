"""Trim the combined Meshtastic guide to the owner-approved sections (keep-or-kill, 2026-11-26).

Keeps: about, getting-started (not flashing-firmware), configuration, hardware/antennas,
software/android/user and the Android index. Drops: hardware/devices catalog, android developer pages,
legal, flashing guides. Device and flashing pages for the owner's radio are added once its model is known.
usage: python -I meshtrim.py in.md out.md
"""
import re
import sys

src, out = sys.argv[1:3]
t = open(src, encoding='utf-8').read()
head, *pages = re.split(r'^(?=## )', t, flags=re.M)
keep_prefix = ('docs/about', 'docs/getting-started', 'docs/configuration', 'docs/hardware/antennas',
               'docs/software/android/user', 'docs/software/android/index')
drop_prefix = ('docs/getting-started/flashing-firmware', 'docs/about/contributing')
kept, dropped = [], 0
for p in pages:
    m = re.search(r'\((docs/[^)]+)\)\s*$', p.split('\n', 1)[0])
    path = m.group(1) if m else ''
    if path.startswith(keep_prefix) and not path.startswith(drop_prefix):
        kept.append(p)
    else:
        dropped += 1
head = head.replace(
    "Pages included: about, getting-started (without serial drivers), configuration, hardware/antennas, "
    "hardware/devices, software/android, legal. Developer pages left out.",
    "Pages included (owner keep-or-kill 2026-11-26): about, getting started, configuration, hardware/antennas, "
    "Android app user pages. Left out: the device catalog, developer pages, legal pages and firmware-flashing "
    "guides (the owner's own radio's pages are added once its model is known).")
md = head + ''.join(kept)
open(out, 'w', encoding='utf-8', newline='\n').write(md)
print(len(kept), 'pages kept,', dropped, 'dropped,', len(md.split()), 'words')
