"""Whitespace-normalised SHA-256 of each <!-- body --> section, to compare with the live page text."""
import hashlib
import re
import sys


def main():
    with open(sys.argv[1], encoding='utf-8') as source:
        text = source.read()
    for body in re.findall(r'<!-- body -->\n(.*?)\n<!-- end -->', text, re.S):
        normalized = re.sub(r'\s+', ' ', body).strip()
        digest = hashlib.sha256(normalized.encode('utf-8')).hexdigest()[:16]
        sys.stdout.buffer.write(f'{digest} {len(normalized)} {normalized[:40]}\n'.encode('utf-8'))


if __name__ == '__main__':
    main()
