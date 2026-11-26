"""Whitespace-normalised SHA-256 of each <!-- body --> section, to compare with the live page text."""
import hashlib
import re
import sys

t = open(sys.argv[1], encoding='utf-8').read()
for b in re.findall(r'<!-- body -->\n(.*?)\n<!-- end -->', t, re.S):
    n = re.sub(r'\s+', ' ', b).strip()
    print(hashlib.sha256(n.encode('utf-8')).hexdigest()[:16], len(n), n[:40])
